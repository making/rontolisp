;;;; raster.lisp -- an integer rasterizer for the page image.
;;;;
;;;; Shapes are polygons in sub-pixel units, 16 to a pixel. A shape is filled by the
;;;; nonzero rule on four sample rows a pixel, each crossing exact to a sixteenth, so a
;;;; pixel's coverage is 0 to 64; it is blended onto the pixel in 17 levels. Curves are
;;;; flattened into lines, a stroke is the union of a quadrilateral a segment and a disc a
;;;; vertex, all the same orientation so the nonzero rule unites them. Every step is
;;;; integer arithmetic, so every backend draws the same pixels.
;;;;
;;;; The image is a palette image: each pixel an index, each blended color added to the
;;;; palette when first met; past 256 colors a new color takes the nearest one.

(in-package :lispresen)

(defparameter *sub* 16 "Sub-pixel units to a pixel.")

(defstruct (image (:constructor %make-image))
  width
  height
  pixels
  palette
  colors)

(defun make-image (width height background)
  (let ((img
         (%make-image :width width :height height
                      :pixels (make-array (* width height)
                                          :element-type '(unsigned-byte 8)
                                          :initial-element 0)
                      :palette (make-array 0 :adjustable t :fill-pointer 0)
                      :colors (make-hash-table))))
    (color-index img background)
    img))

(defun color-index (img rgb)
  "The palette index of RGB, added when new; the nearest color once the palette is full."
  (let ((known (gethash rgb (image-colors img))))
    (cond (known known)
          ((< (length (image-palette img)) 256)
           (vector-push-extend rgb (image-palette img))
           (setf (gethash rgb (image-colors img))
                 (1- (length (image-palette img)))))
          (t
           (let ((best 0) (best-d nil))
             (loop for c across (image-palette img)
                   for k from 0
                   do (let ((d (+ (expt (- (ldb (byte 8 16) c) (ldb (byte 8 16) rgb)) 2)
                                  (expt (- (ldb (byte 8 8) c) (ldb (byte 8 8) rgb)) 2)
                                  (expt (- (ldb (byte 8 0) c) (ldb (byte 8 0) rgb)) 2))))
                        (when (or (null best-d) (< d best-d)) (setf best k best-d d))))
             best)))))

(defun parse-color (s)
  "The #xRRGGBB value of #rgb or #rrggbb."
  (if (= (length s) 4)
      (let ((v 0))
        (loop for k from 1 to 3
              do (let ((d (digit-char-p (char s k) 16)))
                   (setf v (+ (* v 256) (* d 17)))))
        v)
      (parse-integer s :start 1 :radix 16)))

(defun blend (old new level)
  "NEW over OLD at LEVEL of 16."
  (let ((v 0))
    (dolist (shift '(16 8 0))
      (let ((o (ldb (byte 8 shift) old)) (n (ldb (byte 8 shift) new)))
        (setf v (logior v (ash (floor (+ (* o (- 16 level)) (* n level) 8) 16) shift)))))
    v))

(defun paint (img i rgb level)
  (let ((px (image-pixels img)))
    (setf (aref px i)
          (if (= level 16)
              (color-index img rgb)
              (color-index img (blend (aref (image-palette img) (aref px i)) rgb level))))))

;;; --- filling -----------------------------------------------------------------------

;;; An edge is (X0 Y0 X1 Y1 DIR) with Y0 < Y1, DIR +1 for one drawn downward.

(defun add-edge (edges x0 y0 x1 y1)
  (cond ((= y0 y1) edges)
        ((< y0 y1) (cons (list x0 y0 x1 y1 1) edges))
        (t (cons (list x1 y1 x0 y0 -1) edges))))

(defun polygon-edges (points &optional edges)
  "EDGES with those of the closed polygon POINTS, a list of (X . Y)."
  (loop for (p . rest) on points
        do (let ((q (if rest (car rest) (car points))))
             (setf edges (add-edge edges (car p) (cdr p) (car q) (cdr q)))))
  edges)

(defun fill-edges (img edges rgb)
  "Fills the shape EDGES bound with RGB by the nonzero rule."
  (when edges
    (let* ((s *sub*)
           (w (image-width img))
           (x0 (max 0 (floor (reduce #'min edges :key (lambda (e) (min (first e) (third e)))) s)))
           (x1 (min w (ceiling (reduce #'max edges :key (lambda (e) (max (first e) (third e)))) s)))
           (y0 (max 0 (floor (reduce #'min edges :key #'second) s)))
           (y1 (min (image-height img) (ceiling (reduce #'max edges :key #'fourth) s))))
      (when (and (< x0 x1) (< y0 y1))
        (let* ((bw (- x1 x0))
               (cov (make-array (* bw (- y1 y0)) :initial-element 0))
               (pending (sort (copy-list edges) #'< :key #'second))
               (active nil)
               (lo (* x0 s))
               (hi (* x1 s)))
          (loop for py from y0 below y1
                do (dotimes (k 4)
                     (let ((ys (+ (* py s) 2 (* 4 k))) (crossings nil))
                       (loop while (and pending (<= (second (car pending)) ys))
                             do (push (pop pending) active))
                       (setf active (delete-if (lambda (e) (<= (fourth e) ys)) active))
                       (dolist (e active)
                         (destructuring-bind (ex0 ey0 ex1 ey1 dir) e
                           (push (cons (+ ex0 (floor (* (- ex1 ex0) (- ys ey0)) (- ey1 ey0)))
                                       dir)
                                 crossings)))
                       (let ((winding 0) (start 0) (row (* (- py y0) bw)))
                         (dolist (c (sort crossings #'< :key #'car))
                           (let ((before winding))
                             (incf winding (cdr c))
                             (cond ((and (= before 0) (/= winding 0)) (setf start (car c)))
                                   ((and (/= before 0) (= winding 0))
                                    (let ((a (max lo start)) (b (min hi (car c))))
                                      (when (< a b)
                                        (let ((pa (floor a s)) (pb (floor b s)))
                                          (if (= pa pb)
                                              (incf (svref cov (+ row (- pa x0))) (- b a))
                                              (progn
                                                (incf (svref cov (+ row (- pa x0)))
                                                      (- s (mod a s)))
                                                (loop for p from (1+ pa) below pb
                                                      do (incf (svref cov (+ row (- p x0))) s))
                                                (when (< pb x1)
                                                  (incf (svref cov (+ row (- pb x0)))
                                                        (mod b s))))))))))))))))
          (loop for py from y0 below y1
                do (loop for px from x0 below x1
                         do (let ((level (floor (+ (svref cov (+ (* (- py y0) bw) (- px x0))) 2)
                                                4)))
                              (when (> level 0)
                                (paint img (+ (* py w) px) rgb (min level 16)))))))))))

;;; --- shapes ------------------------------------------------------------------------

(defparameter *sine*
  #(0 1608 3216 4821 6424 8022 9616 11204 12785 14359 15924 17479 19024 20557 22078
    23586 25080 26558 28020 29466 30893 32303 33692 35062 36410 37736 39040 40320 41576
    42806 44011 45190 46341 47464 48559 49624 50660 51665 52639 53581 54491 55368 56212
    57022 57798 58538 59244 59914 60547 61145 61705 62228 62714 63162 63572 63944 64277
    64571 64827 65043 65220 65358 65457 65516 65536)
  "65536 sin(k pi / 128) for k from 0 to 64: a quarter turn in 64 steps.")

(defun turn (k)
  "65536 times the cosine and the sine of the angle K / 256 of a turn."
  (let* ((k (mod k 256)) (q (floor k 64)) (r (mod k 64))
         (s (svref *sine* r)) (c (svref *sine* (- 64 r))))
    (case q
      (0 (values c s))
      (1 (values (- s) c))
      (2 (values (- c) (- s)))
      (t (values s (- c))))))

(defun arc-points (cx cy rx ry from to step)
  "The points of the ellipse arc about (CX . CY) with radii RX and RY from the angle FROM
to TO, in 256ths of a turn by STEP, y downward."
  (loop for k = from then (+ k step)
        while (if (> step 0) (<= k to) (>= k to))
        collect (multiple-value-bind (c s) (turn k)
                  (cons (+ cx (floor (* rx c) 65536)) (+ cy (floor (* ry s) 65536))))))

(defun ellipse-points (cx cy rx ry)
  (arc-points cx cy rx ry 0 252 4))

(defun rounded-rect-points (x y w h rx ry)
  (if (or (<= rx 0) (<= ry 0))
      (list (cons x y) (cons (+ x w) y) (cons (+ x w) (+ y h)) (cons x (+ y h)))
      (append (arc-points (- (+ x w) rx) (+ y ry) rx ry 192 256 4)
              (arc-points (- (+ x w) rx) (- (+ y h) ry) rx ry 0 64 4)
              (arc-points (+ x rx) (- (+ y h) ry) rx ry 64 128 4)
              (arc-points (+ x rx) (+ y ry) rx ry 128 192 4))))

(defun fill-rect (img x y w h rgb)
  (fill-edges img (polygon-edges (rounded-rect-points x y w h 0 0)) rgb))

(defun int-sqrt (n)
  "The largest integer whose square is at most N."
  (if (< n 2)
      (max n 0)
      (let ((x n) (y (floor (+ n 1) 2)))
        (loop while (< y x) do (setf x y y (floor (+ x (floor n x)) 2)))
        x)))

(defun oriented (points)
  "POINTS, reversed when they turn the other way, so pieces of a stroke add up."
  (let ((area 0))
    (loop for (p . rest) on points
          do (let ((q (if rest (car rest) (car points))))
               (incf area (- (* (car p) (cdr q)) (* (car q) (cdr p))))))
    (if (< area 0) (reverse points) points)))

(defun stroke-edges (points closed half cap &optional edges)
  "EDGES with the outline of the polyline POINTS stroked HALF wide on each side: round
joins, and CAP (:round, :square or :butt) at the ends of an open one."
  (let ((n (length points)))
    (loop for (p . rest) on points
          for k from 0
          do (let ((q (cond (rest (car rest)) (closed (car points)))))
               (when q
                 (let* ((dx (- (car q) (car p))) (dy (- (cdr q) (cdr p)))
                        (len (int-sqrt (+ (* dx dx) (* dy dy)))))
                   (when (> len 0)
                     (let ((nx (floor (* (- dy) half) len)) (ny (floor (* dx half) len))
                           (ex 0) (ey 0) (fx 0) (fy 0))
                       (when (and (not closed) (eq cap :square))
                         (when (= k 0)
                           (setf ex (floor (* dx half) len) ey (floor (* dy half) len)))
                         (when (= k (- n 2))
                           (setf fx (floor (* dx half) len) fy (floor (* dy half) len))))
                       (setf edges
                             (polygon-edges
                              (oriented (list (cons (- (+ (car p) nx) ex) (- (+ (cdr p) ny) ey))
                                              (cons (+ (car q) nx fx) (+ (cdr q) ny fy))
                                              (cons (- (+ (car q) fx) nx) (- (+ (cdr q) fy) ny))
                                              (cons (- (car p) nx ex) (- (cdr p) ny ey))))
                              edges))))))
               (when (or closed (eq cap :round) (< 0 k (1- n)))
                 (setf edges
                       (polygon-edges (oriented (ellipse-points (car p) (cdr p) half half))
                                      edges)))))
    edges))

(defun quad-points (x0 y0 x1 y1 x2 y2)
  "The points after (X0 . Y0) of the quadratic Bezier curve to (X2 . Y2), flattened to
within an eighth of a pixel."
  (let* ((d (+ (abs (+ x0 (* -2 x1) x2)) (abs (+ y0 (* -2 y1) y2))))
         (n (let ((n 1)) (loop while (and (< n 32) (< (* 16 n n) d)) do (incf n)) n))
         (nn (* n n)))
    (loop for i from 1 to n
          collect (let ((a (* (- n i) (- n i))) (b (* 2 (- n i) i)) (c (* i i)))
                    (cons (floor (+ (* a x0) (* b x1) (* c x2)) nn)
                          (floor (+ (* a y0) (* b y1) (* c y2)) nn))))))

(defun cubic-points (x0 y0 x1 y1 x2 y2 x3 y3)
  "The points after (X0 . Y0) of the cubic Bezier curve to (X3 . Y3), flattened."
  (let* ((d (max (+ (abs (+ x0 (* -2 x1) x2)) (abs (+ y0 (* -2 y1) y2)))
                 (+ (abs (+ x1 (* -2 x2) x3)) (abs (+ y1 (* -2 y2) y3)))))
         (n (let ((n 1)) (loop while (and (< n 32) (< (* 8 n n) (* 3 d))) do (incf n)) n))
         (nnn (* n n n)))
    (loop for i from 1 to n
          collect (let ((a (expt (- n i) 3)) (b (* 3 (- n i) (- n i) i))
                        (c (* 3 (- n i) i i)) (e (* i i i)))
                    (cons (floor (+ (* a x0) (* b x1) (* c x2) (* e x3)) nnn)
                          (floor (+ (* a y0) (* b y1) (* c y2) (* e y3)) nnn))))))

;;; --- text --------------------------------------------------------------------------

(defun contour-points (contour)
  "The closed polygon of a TrueType contour, its points already in sub-pixels as (X Y
ON-CURVE): an off-curve point between two others is a control point, and two off-curve
points in a row have an on-curve point midway."
  (let* ((pts (coerce contour 'vector))
         (n (length pts))
         (first-on (position-if #'third pts)))
    (flet ((pt (k) (svref pts (mod k n)))
           (mid (a b) (list (floor (+ (first a) (first b)) 2)
                            (floor (+ (second a) (second b)) 2) t)))
      (let* ((start (if first-on (pt first-on) (mid (pt 0) (pt 1))))
             (k (if first-on (1+ first-on) 1))
             (stop (+ k n (if first-on -1 0)))
             (cur start)
             (out (list (cons (first start) (second start)))))
        (loop while (< k stop)
              do (let ((p (pt k)))
                   (if (third p)
                       (progn (push (cons (first p) (second p)) out) (setf cur p) (incf k))
                       (let* ((nx (if (< (1+ k) stop) (pt (1+ k)) start))
                              (end (if (third nx) nx (mid p nx))))
                         (dolist (q (quad-points (first cur) (second cur) (first p) (second p)
                                                 (first end) (second end)))
                           (push q out))
                         (setf cur end)
                         (incf k (if (and (third nx) (< (1+ k) stop)) 2 1))))))
        (nreverse out)))))

(defun glyph-edges (font g x y size)
  "The edges of glyph G with its origin at (X . Y), SIZE sub-pixels to the em."
  (let ((units (font-units font)) (edges nil))
    (dolist (contour (glyph-contours font g) edges)
      (when (cdr contour)
        (setf edges
              (polygon-edges
               (contour-points
                (mapcar (lambda (p)
                          (list (+ x (floor (* (first p) size) units))
                                (- y (floor (* (second p) size) units))
                                (third p)))
                        contour))
               edges))))))

(defun text-glyphs (font text)
  "The glyphs of TEXT's characters."
  (map 'list (lambda (c) (glyph-index font (char-code c))) text))

(defun text-advance (font glyphs spacing)
  "The width of GLYPHS in font units, SPACING units after each."
  (reduce #'+ glyphs :key (lambda (g) (+ (glyph-advance font g) spacing))))

(defun draw-text (img font glyphs x y size rgb spacing)
  "Draws GLYPHS from the pen (X . Y) on the baseline, SIZE sub-pixels to the em, SPACING
font units after each glyph."
  (let ((pen 0) (units (font-units font)))
    (dolist (g glyphs)
      (fill-edges img (glyph-edges font g (+ x (floor (* pen size) units)) y size) rgb)
      (incf pen (+ (glyph-advance font g) spacing)))))

;;; --- SVG -----------------------------------------------------------------------------

;;; The logo is drawn from the SVG subset of a simple mark: svg, g, rect, circle, ellipse,
;;; line, polyline, polygon and path (every command but arcs), filled and stroked in
;;; #rgb, #rrggbb, black or white, with stroke-width and stroke-linecap. Joins are round.
;;; Anything else (transforms, styles, gradients, text, arcs) leaves the logo out, so what
;;; is drawn is never a wrong picture.

(defun svg-refuse (what)
  "Gives up drawing the logo; DRAW-SVG answers WHAT."
  (throw 'svg-unsupported what))

(defun svg-tags (text)
  "The elements of TEXT as (NAME ATTRIBUTES CLOSING SELF-CLOSING), in document order."
  (let ((tags nil) (i 0) (n (length text)))
    (loop
      (let ((lt (position #\< text :start i)))
        (unless lt (return))
        (cond ((and (< (+ lt 3) n) (string= "<!--" text :start2 lt :end2 (+ lt 4)))
               (setf i (let ((e (search "-->" text :start2 lt))) (if e (+ e 3) n))))
              ((and (< (1+ lt) n) (find (char text (1+ lt)) "?!"))
               (setf i (1+ (or (position #\> text :start lt) (1- n)))))
              (t
               (let* ((gt (or (position #\> text :start lt) n))
                      (body (subseq text (1+ lt) gt))
                      (closing (and (plusp (length body)) (char= (char body 0) #\/)))
                      (self (and (plusp (length body))
                                 (char= (char body (1- (length body))) #\/)))
                      (name-end (or (position-if (lambda (c) (find c (format nil " ~c~c~c/" #\Tab #\Newline #\Return)))
                                                 body :start (if closing 1 0))
                                    (length body)))
                      (attrs nil)
                      (j name-end))
                 (loop
                   (let ((eq (position #\= body :start j)))
                     (unless eq (return))
                     (let* ((name (string-trim (format nil " ~c~c~c" #\Tab #\Newline #\Return)
                                               (subseq body j eq)))
                            (q (position-if (lambda (c) (find c "\"'")) body :start eq))
                            (end (and q (position (char body q) body :start (1+ q)))))
                       (unless end (return))
                       (push (cons name (subseq body (1+ q) end)) attrs)
                       (setf j (1+ end)))))
                 (push (list (subseq body (if closing 1 0) name-end) (nreverse attrs)
                             closing self)
                       tags)
                 (setf i (1+ gt)))))))
    (nreverse tags)))

(defun svg-number (s start)
  "The number at START of S in thousandths, and the index after it; nil when none."
  (let ((i start) (n (length s)) (sign 1) (mant 0) (dec 0) (digits 0) (exp 0))
    (loop while (and (< i n) (find (char s i) (format nil " ,~c~c~c" #\Tab #\Newline #\Return)))
          do (incf i))
    (when (and (< i n) (find (char s i) "+-"))
      (when (char= (char s i) #\-) (setf sign -1))
      (incf i))
    (loop while (and (< i n) (digit-char-p (char s i)))
          do (setf mant (+ (* mant 10) (digit-char-p (char s i)))) (incf i) (incf digits))
    (when (and (< i n) (char= (char s i) #\.))
      (incf i)
      (loop while (and (< i n) (digit-char-p (char s i)))
            do (setf mant (+ (* mant 10) (digit-char-p (char s i)))) (incf i) (incf dec)
               (incf digits)))
    (when (= digits 0) (return-from svg-number nil))
    (when (and (< i n) (find (char s i) "eE"))
      (multiple-value-bind (e next) (parse-integer s :start (1+ i) :junk-allowed t)
        (when e (setf exp e i next))))
    (let ((scale (- (+ 3 exp) dec)))
      (values (* sign (if (>= scale 0) (* mant (expt 10 scale)) (floor mant (expt 10 (- scale)))))
              i))))

(defun svg-numbers (s)
  (let ((out nil) (i 0))
    (loop (multiple-value-bind (v next) (svg-number s i)
            (unless v (return))
            (push v out)
            (setf i next)))
    (nreverse out)))

(defun svg-paint (value inherited)
  (cond ((null value) inherited)
        ((string= value "none") nil)
        ((color-p value) (parse-color value))
        ((string-equal value "black") 0)
        ((string-equal value "white") #xFFFFFF)
        (t (svg-refuse (format nil "the color ~a" value)))))

(defun svg-path-subpaths (d)
  "The subpaths of the path data D as (POINTS CLOSED), in user thousandths."
  (let ((i 0) (n (length d)) (cmd nil) (x 0) (y 0) (sx 0) (sy 0) (cx nil) (cy nil)
        (cur nil) (subpaths nil))
    (labels ((num ()
               (multiple-value-bind (v next) (svg-number d i)
                 (unless v (svg-refuse "malformed path data"))
                 (setf i next)
                 v))
             (more-p ()
               (multiple-value-bind (v) (svg-number d i) (and v t)))
             (finish (closed)
               (when (cdr cur) (push (list (nreverse cur) closed) subpaths))
               (setf cur nil))
             (to (nx ny) (setf x nx y ny) (push (cons x y) cur)))
      (loop
        (loop while (and (< i n) (find (char d i) (format nil " ,~c~c~c" #\Tab #\Newline #\Return)))
              do (incf i))
        (when (>= i n) (return))
        (let ((c (char d i)))
          (if (alpha-char-p c)
              (progn (setf cmd c) (incf i))
              (unless cmd (svg-refuse "malformed path data"))))
        (let* ((rel (lower-case-p cmd)) (ox (if rel x 0)) (oy (if rel y 0))
               (k (char-upcase cmd)) (next-cx nil) (next-cy nil))
          (case k
            (#\M (finish nil)
             (let ((nx (+ ox (num))) (ny (+ oy (num))))
               (setf sx nx sy ny)
               (to nx ny))
             (setf cmd (if rel #\l #\L)))
            (#\L (to (+ ox (num)) (+ oy (num))))
            (#\H (to (+ ox (num)) y))
            (#\V (to x (+ oy (num))))
            ((#\Q #\T)
             (let* ((x1 (if (char= k #\Q) (+ ox (num)) (if cx (- (* 2 x) cx) x)))
                    (y1 (if (char= k #\Q) (+ oy (num)) (if cy (- (* 2 y) cy) y)))
                    (x2 (+ ox (num))) (y2 (+ oy (num))))
               (unless cur (push (cons x y) cur))
               (dolist (p (quad-points x y x1 y1 x2 y2)) (push p cur))
               (setf x x2 y y2 next-cx x1 next-cy y1)
               (setf cx next-cx cy next-cy)))
            ((#\C #\S)
             (let* ((x1 (if (char= k #\C) (+ ox (num)) (if cx (- (* 2 x) cx) x)))
                    (y1 (if (char= k #\C) (+ oy (num)) (if cy (- (* 2 y) cy) y)))
                    (x2 (+ ox (num))) (y2 (+ oy (num)))
                    (x3 (+ ox (num))) (y3 (+ oy (num))))
               (unless cur (push (cons x y) cur))
               (dolist (p (cubic-points x y x1 y1 x2 y2 x3 y3)) (push p cur))
               (setf x x3 y y3 next-cx x2 next-cy y2)
               (setf cx next-cx cy next-cy)))
            (#\Z (finish t) (setf x sx y sy) (push (cons x y) cur) (setf cmd nil))
            (t (svg-refuse (format nil "the path command ~a" cmd))))
          (unless (member k '(#\Q #\T #\C #\S)) (setf cx nil cy nil))
          (when (and (eql k #\Z) (more-p)) (svg-refuse "malformed path data"))))
      (finish nil))
    (nreverse subpaths)))

(defparameter *svg-paint-attributes*
  '("fill" "stroke" "stroke-width" "stroke-linecap" "stroke-linejoin"))

(defparameter *svg-ignored-attributes*
  '("xmlns" "xmlns:xlink" "version" "id" "class" "viewBox" "width" "height" "x" "y" "rx"
    "ry" "cx" "cy" "r" "x1" "y1" "x2" "y2" "points" "d" "role" "aria-label"
    "aria-hidden" "focusable" "preserveAspectRatio" "stroke-miterlimit"))

(defun svg-shapes (text)
  "The shapes of the SVG TEXT as (SUBPATHS FILL STROKE STROKE-WIDTH CAP), in user
thousandths, and its view box as (X Y W H)."
  (let ((stack (list (list 0 nil 1000 :butt))) (shapes nil) (view nil) (skip 0))
    (dolist (tag (svg-tags text))
      (destructuring-bind (name attrs closing self) tag
        (flet ((attr (a) (cdr (assoc a attrs :test #'string=)))
               (num (a) (let ((v (cdr (assoc a attrs :test #'string=))))
                          (if v (or (svg-number v 0) 0) 0))))
          (cond ((member name '("title" "desc" "metadata") :test #'string=)
                 (unless self (incf skip (if closing -1 1))))
                ((> skip 0))
                (closing (when (string= name "g") (pop stack)))
                (t
                 (dolist (a attrs)
                   (unless (or (member (car a) *svg-paint-attributes* :test #'string=)
                               (member (car a) *svg-ignored-attributes* :test #'string=))
                     (svg-refuse (format nil "the attribute ~a" (car a)))))
                 (destructuring-bind (fill stroke width cap) (car stack)
                   (let* ((fill (svg-paint (attr "fill") fill))
                          (stroke (svg-paint (attr "stroke") stroke))
                          (width (if (attr "stroke-width") (num "stroke-width") width))
                          (cap (let ((c (attr "stroke-linecap")))
                                 (cond ((null c) cap)
                                       ((string= c "round") :round)
                                       ((string= c "square") :square)
                                       (t :butt))))
                          (paint (list fill stroke width cap)))
                     (flet ((shape (subpaths) (push (cons subpaths paint) shapes)))
                       (cond ((string= name "svg")
                              (let ((vb (and (attr "viewBox") (svg-numbers (attr "viewBox")))))
                                (setf view (if (= (length vb) 4)
                                               vb
                                               (list 0 0 (num "width") (num "height")))))
                              (setf (car stack) paint))
                             ((string= name "g") (unless self (push paint stack)))
                             ((string= name "rect")
                              (let* ((rx (if (attr "rx") (num "rx") (num "ry")))
                                     (ry (if (attr "ry") (num "ry") rx))
                                     (w (num "width")) (h (num "height")))
                                (shape (list (list (rounded-rect-points
                                                    (num "x") (num "y") w h
                                                    (min rx (floor w 2)) (min ry (floor h 2)))
                                                   t)))))
                             ((string= name "circle")
                              (shape (list (list (ellipse-points (num "cx") (num "cy") (num "r") (num "r")) t))))
                             ((string= name "ellipse")
                              (shape (list (list (ellipse-points (num "cx") (num "cy") (num "rx") (num "ry")) t))))
                             ((string= name "line")
                              (shape (list (list (list (cons (num "x1") (num "y1"))
                                                       (cons (num "x2") (num "y2")))
                                                 nil))))
                             ((member name '("polyline" "polygon") :test #'string=)
                              (let ((ns (svg-numbers (or (attr "points") ""))))
                                (shape (list (list (loop for (a b) on ns by #'cddr
                                                         when b collect (cons a b))
                                                   (string= name "polygon"))))))
                             ((string= name "path")
                              (shape (svg-path-subpaths (or (attr "d") ""))))
                             (t (svg-refuse (format nil "the element ~a" name))))))))))))
    (unless (and view (> (third view) 0) (> (fourth view) 0))
      (svg-refuse "a missing view box"))
    (values (nreverse shapes) view)))

(defun svg-view (text)
  "The view box (X Y W H) of the SVG TEXT, in user thousandths, or what of it cannot be
drawn as a string."
  (catch 'svg-unsupported (nth-value 1 (svg-shapes text))))

(defun draw-svg (img text left top height)
  "Draws the SVG TEXT HEIGHT sub-pixels tall from (LEFT . TOP), as wide as its view box
makes it."
  (multiple-value-bind (shapes view) (svg-shapes text)
    (let ((vx (first view)) (vy (second view)) (vh (fourth view)))
      (flet ((at (p) (cons (+ left (floor (* (- (car p) vx) height) vh))
                           (+ top (floor (* (- (cdr p) vy) height) vh)))))
        (dolist (s shapes)
          (destructuring-bind (subpaths fill stroke width cap) s
            (let ((paths (mapcar (lambda (sp) (list (mapcar #'at (first sp)) (second sp)))
                                 subpaths)))
              (when fill
                (let ((edges nil))
                  (dolist (p paths) (setf edges (polygon-edges (first p) edges)))
                  (fill-edges img edges fill)))
              (when (and stroke (> width 0))
                (let ((edges nil) (half (floor (* width height) (* 2 vh))))
                  (dolist (p paths)
                    (setf edges (stroke-edges (first p) (second p) half cap edges)))
                  (fill-edges img edges stroke))))))))))
