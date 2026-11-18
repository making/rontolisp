;;;; pptx.lisp -- the PPTX emitter: the layout lispresen computed, written as DrawingML.
;;;;
;;;; Colors are theme references (schemeClr), and ordinary text names no typeface at all,
;;;; so the destination's default text style and theme fonts apply. Code is the exception:
;;;; it is monospace, which a theme has no slot for.
;;;; Each text line is a text box at the line's place the fit solver computed; a code
;;;; panel, a table's rules, a diagram, a formula, a picture and a callout are shapes.
;;;; Coordinates are integer EMU (6350 a canvas px); a size is px * 50 hundredths of a
;;;; point (56 px is 28 pt on a 7.5 in slide).

(in-package :lispresen)

(defun emu (px) (* px 6350))
(defun sz100 (px) (* px 50))

(defun write-xml-text (s out)
  (loop for c across s
        do (let ((code (char-code c)))
             (if (or (= code 9) (= code 10) (= code 13) (>= code 32))
                 (write-escaped-char (if (or (= code 10) (= code 13)) #\Space c) out)
                 (write-char #\Space out)))))

(defun write-xml-attr (s out)
  (write-xml-text s out))

;;; --- media ---------------------------------------------------------------------------

(defvar *pptx-media* nil "An alist of (SRC . PART) for the media of the deck.")
(defvar *pptx-media-count* 0)
(defvar *pptx-media-bytes* nil "An alist of (PART-NAME . OCTETS).")
(defvar *pptx-slide-rels* nil)
(defvar *shape-id* 0)

(defun next-shape ()
  (incf *shape-id*))

(defun add-rel (type target &optional external)
  (let ((id (format nil "rId~d" (1+ (length *pptx-slide-rels*)))))
    (push (list id type target external) *pptx-slide-rels*)
    id))

(defun media-target (part)
  (concatenate 'string "../media/" (subseq (first part)
                                           (length "ppt/media/"))))

(defun image-extension (src)
  (string-downcase (or (extension src) "")))

(defun rasterized-svg (text view)
  "TEXT, an SVG in the drawing subset, as PNG octets and its pixel size: drawn 540 px
tall, or as tall as its view box when that is smaller."
  (let* ((vh (fourth view))
         (vw (third view))
         (h (min 540 (max 1 (round vh 1000))))
         (w (max 1 (round (* h vw) vh)))
         (img (make-image w h #xFFFFFF)))
    (draw-svg img text 0 0 (* h *sub*))
    (values (png-octets w h (image-palette img) (image-pixels img)) w h)))

(defun store-media (src name ext w h octets)
  (let ((part (list name ext w h)))
    (push (cons src part) *pptx-media*)
    (when octets (push (cons name octets) *pptx-media-bytes*))
    part))

(defun media-part (src)
  "The media part of SRC as (NAME EXT WIDTH HEIGHT), embedded once; nil, reported, when
the file cannot be read or its size is unknown."
  (let ((known (assoc src *pptx-media* :test #'equal)))
    (if known
        (cdr known)
        (let ((octets (and *read-file* (funcall *read-file* src))))
          (cond
            ((null octets)
             (report :warning (format nil "PPTX: cannot read image ~a" src))
             nil)
            ((string-equal (image-extension src) "svg")
             (let* ((text (utf-8-string octets))
                    (view (and text (svg-view text))))
               (if (consp view)
                   (multiple-value-bind (png pw ph) (rasterized-svg text view)
                     (store-media src
                                  (format nil "ppt/media/image~d.png"
                                          (incf *pptx-media-count*))
                                  "png" pw ph png))
                   (progn
                     (report :warning
                             (format nil
                                     "PPTX: ~a is outside the SVG subset lispresen draws; it is embedded as SVG"
                                     src))
                     (multiple-value-bind (w h) (image-size octets)
                       (store-media src
                                    (format nil "ppt/media/image~d.svg"
                                            (incf *pptx-media-count*))
                                    "svg" w h octets))))))
            (t
             (multiple-value-bind (w h) (image-size octets)
               (if (null w)
                   (progn
                     (report :warning
                             (format nil "PPTX: cannot read the size of ~a" src))
                     nil)
                   (store-media src
                                (format nil "ppt/media/image~d.~a"
                                        (incf *pptx-media-count*)
                                        (image-extension src))
                                (image-extension src) w h octets)))))))))

(defun media-bytes (part)
  (cdr (assoc (first part) *pptx-media-bytes* :test #'equal)))

;;; --- shapes --------------------------------------------------------------------------

(defun emit-fill (out fill)
  (cond ((null fill) (write-string "<a:noFill/>" out))
        ((eq (car fill) :rgb)
         (format out "<a:solidFill><a:srgbClr val=\"~a\"/></a:solidFill>" (second fill)))
        (t
         (format out "<a:solidFill><a:schemeClr val=\"~a\"/></a:solidFill>"
                 (second fill)))))

(defun emit-outline (out fill width)
  (if (null fill)
      (write-string "<a:ln><a:noFill/></a:ln>" out)
      (progn
        (format out "<a:ln w=\"~d\">" (emu width))
        (emit-fill out fill)
        (write-string "<a:prstDash val=\"solid\"/></a:ln>" out))))

(defun sp-rect (out x y w h fill &key rx outline (outline-width 3))
  (let ((id (next-shape)))
    (format out
            "<p:sp><p:nvSpPr><p:cNvPr id=\"~d\" name=\"Shape ~d\"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr><p:spPr><a:xfrm><a:off x=\"~d\" y=\"~d\"/><a:ext cx=\"~d\" cy=\"~d\"/></a:xfrm>"
            id id (emu x) (emu y) (emu w) (emu h))
    (if (and rx (> rx 0) (> w 0) (> h 0))
        (format out
                "<a:prstGeom prst=\"roundRect\"><a:avLst><a:gd name=\"adj\" fmla=\"val ~d\"/></a:avLst></a:prstGeom>"
                (min 50000 (round (* rx 100000) (min w h))))
        (write-string "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom>" out))
    (emit-fill out fill)
    (emit-outline out outline (or outline-width 3))
    (write-string "</p:spPr></p:sp>" out)))

(defun sp-path (out x y w h path fill &key outline (outline-width 3))
  "A shape of x, y, w, h whose geometry is PATH, a string in the path units of W x H."
  (let ((id (next-shape)))
    (format out
            "<p:sp><p:nvSpPr><p:cNvPr id=\"~d\" name=\"Shape ~d\"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr><p:spPr><a:xfrm><a:off x=\"~d\" y=\"~d\"/><a:ext cx=\"~d\" cy=\"~d\"/></a:xfrm>"
            id id (emu x) (emu y) (emu w) (emu h))
    (format out
            "<a:custGeom><a:avLst/><a:gdLst/><a:ahLst/><a:cxnLst/><a:rect l=\"0\" t=\"0\" r=\"r\" b=\"b\"/><a:pathLst><a:path w=\"~d\" h=\"~d\" fill=\"norm\" stroke=\"false\">~a</a:path></a:pathLst></a:custGeom>"
            (emu w) (emu h) path)
    (emit-fill out fill)
    (emit-outline out outline outline-width)
    (write-string "</p:spPr></p:sp>" out)))

(defun poly-path (points)
  "The path of POINTS, (X . Y) pairs relative to their own left and top."
  (with-output-to-string (out)
    (loop for (x . y) in points
          for first = t then nil
          do (format out "<a:~a><a:pt x=\"~d\" y=\"~d\"/></a:~a>"
                     (if first "moveTo" "lnTo") (emu x) (emu y)
                     (if first "moveTo" "lnTo")))
    (write-string "<a:close/>" out)))

(defun sp-poly (out points fill &key outline (outline-width 3))
  (let* ((xs (mapcar #'car points))
         (ys (mapcar #'cdr points))
         (l (reduce #'min xs)) (t0 (reduce #'min ys))
         (r (reduce #'max xs)) (b (reduce #'max ys))
         (w (- r l)) (h (- b t0)))
    (when (and (> w 0) (> h 0))
      (sp-path out l t0 w h
               (poly-path (mapcar (lambda (p) (cons (- (car p) l) (- (cdr p) t0)))
                                  points))
               fill :outline outline :outline-width outline-width))))

(defun angle-of (cx cy x y)
  "The angle of (X, Y) from (CX, CY) in 1/60000 degree, clockwise from the x axis."
  (let ((deg (* 180 (/ (atan (- y cy) (- x cx)) pi))))
    (mod (round (* deg 60000)) (* 360 60000))))

(defun turn-delta (a b)
  "The clockwise angle from A to B, in 1/60000 degree, in (0, 360)."
  (let ((d (mod (- b a) (* 360 60000))))
    (if (zerop d) (* 360 60000) d)))

(defun slice-center (x1 y1 x2 y2 x3 y3 x4 y4 r ri)
  "The center of a pie slice: the point at R px from both outer edge points and RI from
both inner ones. The two candidates the outer chord leaves are told apart by the inner
points, which are the center for a pie. Floats only pick the integers the path is
written with."
  (let* ((mx (/ (+ x1 x2) 2.0)) (my (/ (+ y1 y2) 2.0))
         (dx (- x2 x1)) (dy (- y2 y1))
         (d (sqrt (+ (* dx dx) (* dy dy))))
         (h (if (> d 0.0)
                (sqrt (max 0.0 (- (* r r) (/ (* d d) 4.0))))
                0.0))
         (ux (if (> d 0.0) (/ (- dy) d) 0.0))
         (uy (if (> d 0.0) (/ dx d) 1.0)))
    (flet ((score (cx cy)
             (+ (abs (- (sqrt (+ (expt (- x3 cx) 2) (expt (- y3 cy) 2))) ri))
                (abs (- (sqrt (+ (expt (- x4 cx) 2) (expt (- y4 cy) 2))) ri))))
           (at (sign)
             (list (+ mx (* sign h ux)) (+ my (* sign h uy)))))
      (let ((a (at 1.0)) (b (at -1.0)))
        (if (<= (score (first a) (second a)) (score (first b) (second b)))
            (values (first a) (second a))
            (values (first b) (second b)))))))

(defun arc-samples (cx cy r a-from a-to)
  "Points of the arc from A-FROM to A-TO (1/60000 degree, clockwise), two degrees
apart, both ends included."
  (let* ((step 120000)
         (n (max 1 (ceiling (abs (- a-to a-from)) step)))
         (points nil))
    (loop for k from 0 to n
          for a = (+ a-from (round (* k (- a-to a-from)) n))
          do (let ((rad (* pi (/ a 60000.0 180.0))))
               (push (cons (round (+ cx (* r (cos rad))))
                           (round (+ cy (* r (sin rad)))))
                     points)))
    (nreverse points)))

(defun sp-slice (out class box r ri large x1 y1 x2 y2 x3 y3 x4 y4)
  "A pie slice as a filled polygon: the outer arc, the inner edge and back, two degrees
at a point. A path of arcs would be shorter, but renderers disagree on arcTo."
  (declare (ignore large box))
  (multiple-value-bind (cx cy)
      (if (zerop ri)
          (values x3 y3)
          (slice-center x1 y1 x2 y2 x3 y3 x4 y4 r ri))
    (let* ((a1 (angle-of cx cy x1 y1))
           (sweep (turn-delta a1 (angle-of cx cy x2 y2)))
           (a2 (+ a1 sweep))
           (outer (arc-samples cx cy r a1 a2))
           (inner (if (> ri 0)
                      (arc-samples cx cy ri a2 a1)
                      (list (cons (round cx) (round cy))))))
      (sp-poly out (append outer inner) (diagram-fill class)))))

(defun diagram-fill (class)
  "The theme color a diagram shape's class takes."
  (cond ((equal class "dg-b") '(:scheme "accent1"))
        ((equal class "dg-f") '(:scheme "bg2"))
        ((equal class "dg-e") '(:scheme "tx1"))
        ((equal class "dg-g") '(:scheme "tx2"))
        ((equal class "dg-z") '(:scheme "tx2"))
        ((equal class "dg-bg") '(:scheme "bg1"))
        ((and (>= (length class) 5) (equal (subseq class 0 4) "dg-s")
              (find (char class 4) "123456"))
         (list :scheme (format nil "accent~c" (char class 4))))
        ((and (>= (length class) 5) (equal (subseq class 0 4) "dg-r"))
         '(:scheme "bg1"))
        (t '(:scheme "tx1"))))

;;; --- text ----------------------------------------------------------------------------

(defparameter *pptx-ascent* 880)
(defparameter *pptx-descent* 120)

(defun role-color (role)
  (case role
    ((:lead :caption :cite :meta :tick :edge-label) "tx2")
    (t "tx1")))

(defun emit-run (out text attrs s role)
  (let* ((link (getf attrs :link))
         (accent (and (member :accent attrs) t))
         (mono (and (member :mono attrs) t))
         (mark (and (member :mark attrs) t)))
    (write-string "<a:r><a:rPr lang=\"ja-JP\"" out)
    (format out " sz=\"~d\"" (sz100 s))
    (when (member :bold attrs) (write-string " b=\"1\"" out))
    (when (member :italic attrs) (write-string " i=\"1\"" out))
    (when link (write-string " u=\"sng\"" out))
    (write-string " dirty=\"0\"><a:solidFill>" out)
    (format out "<a:schemeClr val=\"~a\"/>"
            (cond (link "hlink")
                  (accent "accent1")
                  (t (role-color role))))
    (write-string "</a:solidFill>" out)
    (when (or mark mono)
      (write-string "<a:highlight><a:schemeClr val=\"bg2\"/></a:highlight>" out))
    ;; No typeface for ordinary text: the destination's default text style and theme
    ;; fonts apply. Code is monospace, which a theme has no slot for.
    (when mono
      (write-string "<a:latin typeface=\"Consolas\"/><a:ea typeface=\"Consolas\"/>" out))
    (when link
      (format out "<a:hlinkClick r:id=\"~a\"/>" (add-rel *hyperlink-rel* link t)))
    (write-string "</a:rPr><a:t>" out)
    (write-xml-text text out)
    (write-string "</a:t></a:r>" out)))

(defun emit-run-text (out run start end s role)
  (let ((attrs (run-attrs run)))
    (if (null attrs)
        (emit-run out (subseq (run-text run) start end) nil s role)
        (let ((i start))
          (loop while (< i end)
                do (let* ((a (svref attrs i))
                          (j (1+ i)))
                     (loop while (and (< j end) (equal (svref attrs j) a)) do (incf j))
                     (emit-run out (subseq (run-text run) i j) a s role)
                     (setf i j)))))))

(defun emit-paragraph-props (out s lh algn)
  (write-string "<a:p><a:pPr" out)
  (when algn (format out " algn=\"~a\"" algn))
  (format out
          "><a:lnSpc><a:spcPts val=\"~d\"/></a:lnSpc><a:spcBef><a:spcPts val=\"0\"/></a:spcBef><a:spcAft><a:spcPts val=\"0\"/></a:spcAft></a:pPr>"
          (* lh 50)))

(defun emit-end-para (out s)
  (format out "<a:endParaRPr lang=\"ja-JP\" sz=\"~d\"/></a:p>" (sz100 s)))

(defun sp-text (out x y w h s lh algn body &key anchor)
  "A text box at X, Y, W x H holding BODY, which writes the paragraphs."
  (let ((id (next-shape)))
    (format out
            "<p:sp><p:nvSpPr><p:cNvPr id=\"~d\" name=\"Text ~d\"/><p:cNvSpPr txBox=\"1\"/><p:nvPr/></p:nvSpPr><p:spPr><a:xfrm><a:off x=\"~d\" y=\"~d\"/><a:ext cx=\"~d\" cy=\"~d\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom><a:noFill/></p:spPr><p:txBody><a:bodyPr wrap=\"none\" lIns=\"0\" tIns=\"0\" rIns=\"0\" bIns=\"0\" anchor=\"~a\"><a:noAutofit/></a:bodyPr><a:lstStyle/>"
            id id (emu x) (emu y) (emu (max 1 w)) (emu (max 1 h))
            (or anchor "t"))
    (funcall body out)
    (write-string "</p:txBody></p:sp>" out)))

(defun emit-line-box (out run start end s lh x y role &optional limit)
  "One line of RUN, with an inline formula overlaid where it stands. LIMIT, when given,
is the right edge the box may reach: the line keeps its x, but a box that is wider than
the line leaves room for a font that sets it wider, so an import does not wrap it."
  (let* ((text (run-text run))
         (formulas (remove-if-not (lambda (f) (and (>= (car f) start) (< (car f) end)))
                                  (run-formulas run)))
         (pos start)
         (segments nil))
    (dolist (f formulas)
      (when (> (car f) pos)
        (push (list pos (car f)) segments))
      (push (list 'formula (car f) (cdr f) (car f)) segments)
      (setf pos (1+ (car f))))
    (when (< pos end) (push (list pos end) segments))
    (dolist (seg (nreverse segments))
      (if (eq (car seg) 'formula)
          (destructuring-bind (marker idx f endidx) seg
            (declare (ignore marker endidx))
            (let* ((prefix (floor (run-span-width run start idx s t) 1000))
                   (size (max 1 s))
                   (baseline (+ y (line-baseline-offset lh s))))
              (declare (ignore size))
              (emit-formula out f (+ x prefix) baseline s)))
          (destructuring-bind (a b) seg
            (when (> b a)
              (let* ((lw (milli-px->px (run-span-width run a b s t)))
                     (px (+ x (floor (run-span-width run start a s t) 1000)))
                     (pw (max 1 (if (and limit (> limit px)) (max lw (- limit px)) lw))))
                (sp-text out px y pw lh s lh nil
                         (lambda (o)
                           (emit-paragraph-props o s lh nil)
                           (emit-run-text o run a b s role)
                           (emit-end-para o s))))))))))

(defun line-baseline-offset (lh s)
  "The baseline of a line box LH tall at S px, as the font stack centers it."
  (+ (floor (- (* lh 1000) (* (+ *pptx-ascent* *pptx-descent*) s)) 2000)
     (floor (* *pptx-ascent* s) 1000)))

(defun emit-text-record (out rec)
  (destructuring-bind (kind run s lh x y width align role info) rec
    (declare (ignore kind info))
    (dolist (line (run-line-boxes run s width lh x y align))
      (destructuring-bind (start end top lx lw) line
        (declare (ignore lw))
        (when (< start end)
          (emit-line-box out run start end s lh lx top role (+ x width)))))))

(defun emit-paragraph-record (out rec)
  "A paragraph as one box, its lines as explicit breaks. A paragraph with an inline
formula keeps a box a line, since the formula's glyphs are placed between segments."
  (destructuring-bind (kind run s lh x y width align role info) rec
    (declare (ignore kind info))
    (if (run-formulas run)
        (emit-text-record out rec)
        (emit-lines-paragraph out run s lh x y width align role))))

(defun emit-list-group (out items)
  "A list as one text box: a native bullet or number paragraph an item, at the indent
the layout gave it, with the gap it put between items."
  (let* ((items (sort (copy-list items) #'< :key (lambda (r) (nth 5 r))))
         (laid (loop for rec in items
                     collect
                       (list rec
                             (run-line-boxes (nth 1 rec) (nth 2 rec) (nth 6 rec)
                                             (nth 3 rec) 0 0))))
         (shape-x (reduce #'min
                          (mapcar (lambda (r)
                                    (- (nth 4 r) (getf (nth 9 r) :indent)))
                                  items)))
         (first-y (nth 5 (first items)))
         (right (reduce #'max
                        (mapcar (lambda (r) (+ (nth 4 r) (nth 6 r))) items)))
         (bottom (reduce #'max
                         (loop for (rec lines) in laid
                               collect (+ (nth 5 rec)
                                          (* (length lines) (nth 3 rec)))))))
    (sp-text out shape-x first-y (max 1 (- right shape-x))
             (max 1 (- bottom first-y)) (nth 2 (first items)) (nth 3 (first items))
             nil
             (lambda (o)
               (let ((prev first-y))
                 (dolist (pair laid)
                   (destructuring-bind (rec lines) pair
                     (destructuring-bind (kind run s lh x y width align role info) rec
                       (declare (ignore kind align role))
                       (let* ((ordered (getf info :ordered))
                              (index (getf info :index))
                              (marker (if ordered
                                          (format nil "~d." (1+ index))
                                          "•"))
                              (bullet
                                (if ordered
                                    (format nil
                                            "<a:buAutoNum type=\"arabicPeriod\" startAt=\"~d\"/>"
                                            (1+ index))
                                    "<a:buChar char=\"•\"/>"))
                              (mar-l (- x shape-x))
                              ;; Put the marker just left of the text, as the layout does,
                              ;; not at the far end of the indent.
                              (marker-pos
                                (max 0 (- mar-l
                                          (+ (run-full-width
                                              (make-text-run (list marker)) s)
                                             4))))
                              (gap (max 0 (- y prev))))
                         (format o
                                 "<a:p><a:pPr marL=\"~d\" indent=\"~d\"><a:lnSpc><a:spcPts val=\"~d\"/></a:lnSpc><a:spcBef><a:spcPts val=\"~d\"/></a:spcBef><a:spcAft><a:spcPts val=\"0\"/></a:spcAft>~a</a:pPr>"
                                 (emu mar-l) (emu marker-pos) (* lh 50)
                                 (* gap 50) bullet)
                         (let ((first t))
                           (dolist (line lines)
                             (destructuring-bind (start end top lx lw) line
                               (declare (ignore top lx lw))
                               (unless first (write-string "<a:br/>" o))
                               (setf first nil)
                               (table-cell-runs o run start end s))))
                         (emit-end-para o s)
                         (setf prev (+ y (* (length lines) lh))))))))))))

;;; --- code ----------------------------------------------------------------------------

(defun code-class-color (class)
  (cond ((equal class "c") "tx2")
        (t "tx1")))

(defun emit-code-row (out line start end s x y lang)
  "One row of a code line, highlighted by the same tokenizer the HTML uses."
  (let ((segments (highlight-segments line lang))
        (i start))
    (loop while (< i end)
          do (let* ((seg (find-if (lambda (sg) (and (< (first sg) end)
                                                    (> (second sg) i)))
                                  segments))
                    (a (max i (first seg)))
                    (b (min end (second seg)))
                    (class (third seg)))
               (when (> b a)
                 (sp-text
                  out (+ x (floor (mono-width (subseq line start a) s))) y
                  (max 1 (mono-width (subseq line a b) s)) (code-lh s) s (code-lh s)
                  nil
                  (lambda (o)
                    (emit-paragraph-props o s (code-lh s) nil)
                    (let ((bold (member class '("k" "d") :test #'equal))
                          (color (code-class-color class)))
                      (write-string "<a:r><a:rPr lang=\"ja-JP\"" o)
                      (format o " sz=\"~d\"~a dirty=\"0\"><a:solidFill><a:schemeClr val=\"~a\"/></a:solidFill><a:latin typeface=\"Consolas\"/><a:ea typeface=\"Consolas\"/></a:rPr><a:t>"
                              (sz100 s) (if bold " b=\"1\"" "") color)
                      (write-xml-text (subseq line a b) o)
                      (write-string "</a:t></a:r>" o))
                    (emit-end-para o s))))
               (setf i (max b (1+ i)))))))

(defun emit-code (out front rec)
  (destructuring-bind (kind b c x y width height) rec
    (declare (ignore kind))
    (sp-rect front x y width height '(:scheme "bg2")
             :rx 12)
    (let ((pad (code-pad c))
          (top-pad (code-top-pad b c width))
          (lh (code-lh c))
          (row 0))
      (dolist (r (code-rows b c width))
        (destructuring-bind (line start end) r
          (when (< start end)
            (emit-code-row out line start end c (+ x pad) (+ y top-pad (* row lh))
                           (code-block-lang b)))
          (incf row))))))

;;; --- table ---------------------------------------------------------------------------

(defun table-cell-runs (out run start end s)
  "The runs of one cell line. An inline formula has no position inside a table cell, so
it becomes as many spaces as its width."
  (let ((pos start)
        (formulas (remove-if-not (lambda (f) (and (>= (car f) start) (< (car f) end)))
                                 (run-formulas run))))
    (dolist (f formulas)
      (when (> (car f) pos)
        (emit-run-text out run pos (car f) s :body))
      (let* ((fw (svref (run-widths run) (car f)))
             (sw (char-width 32 :normal))
             (n (max 1 (round fw sw))))
        (emit-run out (make-string n :initial-element #\Space) nil s :body))
      (setf pos (1+ (car f))))
    (when (< pos end)
      (emit-run-text out run pos end s :body))))

(defun emit-native-table (out rec)
  "A table as PowerPoint's own table: its cells hold the text at the size and wrap the
fit solver chose, its rows are the computed heights, and its style adds no fill or rule
of its own (the header fill and the row rules are set here)."
  (destructuring-bind (kind tb x y width height s) rec
    (declare (ignore kind height))
    (let* ((widths (or (table-block-widths tb) (list width)))
           (aligns (table-block-aligns tb))
           (rows (table-rows-with-header tb))
           (lh (table-lh s))
           (px (cell-pad-x s))
           (py (cell-pad-y s))
           (row-heights
             (loop for row in rows
                   collect
                     (let ((tallest 0) (col 0))
                       (dolist (c row)
                         (let ((w (reduce #'+ (subseq widths (min col (length widths))
                                                     (min (+ col (cell-colspan c))
                                                          (length widths))))))
                           (setf tallest
                                 (max tallest
                                      (run-lines (cell-run c) s
                                                 (max 1 (- w (* 2 px))))))
                           (incf col (cell-colspan c))))
                       (+ (* tallest lh) (* 2 py)))))
           (total (reduce #'+ widths))
           (th (reduce #'+ row-heights))
           (id (next-shape)))
      (format out
              "<p:graphicFrame><p:nvGraphicFramePr><p:cNvPr id=\"~d\" name=\"Table ~d\"/><p:cNvGraphicFramePr><a:graphicFrameLocks noGrp=\"1\"/></p:cNvGraphicFramePr><p:nvPr/></p:nvGraphicFramePr><p:xfrm><a:off x=\"~d\" y=\"~d\"/><a:ext cx=\"~d\" cy=\"~d\"/></p:xfrm><a:graphic><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/table\"><a:tbl><a:tblPr firstRow=\"0\" firstCol=\"0\" lastRow=\"0\" lastCol=\"0\" bandRow=\"0\" bandCol=\"0\"><a:tableStyleId>{2D5ABB26-0587-4C30-8999-92F81FD0307C}</a:tableStyleId></a:tblPr><a:tblGrid>"
              id id (emu x) (emu y) (emu total) (emu th))
      (dolist (w widths) (format out "<a:gridCol w=\"~d\"/>" (emu w)))
      (write-string "</a:tblGrid>" out)
      (loop for row in rows
            for rh in row-heights
            for row-index from 0
            do
              (format out "<a:tr h=\"~d\">" (emu rh))
              (let ((col 0) (cx x))
                (dolist (c row)
                  (let* ((n (cell-colspan c))
                         (w (reduce #'+ (subseq widths (min col (length widths))
                                                (min (+ col n) (length widths)))))
                         (inner (max 1 (- w (* 2 px))))
                         (align (and aligns (nth col aligns))))
                    (format out "<a:tc~a><a:txBody><a:bodyPr wrap=\"none\"><a:noAutofit/></a:bodyPr><a:lstStyle/>"
                            (if (> n 1) (format nil " gridSpan=\"~d\"" n) ""))
                    (emit-paragraph-props
                     out s lh
                     (cond ((equal align "right") "r")
                           ((equal align "center") "ctr")
                           (t "l")))
                    (let ((first t))
                      (dolist (line (run-line-boxes (cell-run c) s inner lh 0 0))
                        (destructuring-bind (start end top lx lw) line
                          (declare (ignore top lx lw))
                          (unless first (write-string "<a:br/>" out))
                          (setf first nil)
                          (table-cell-runs out (cell-run c) start end s))))
                    (emit-end-para out s)
                    (format out
                            "</a:txBody><a:tcPr marL=\"~d\" marR=\"~d\" marT=\"~d\" marB=\"~d\" anchor=\"t\">"
                            (emu px) (emu px) (emu py) (emu py))
                    (format out
                            "<a:lnB w=\"~d\" cap=\"flat\" cmpd=\"sng\" algn=\"ctr\"><a:solidFill><a:schemeClr val=\"tx2\"/></a:solidFill><a:prstDash val=\"solid\"/></a:lnB>"
                            (emu 1))
                    (if (and (zerop row-index) (table-block-header tb))
                        (write-string "<a:solidFill><a:schemeClr val=\"bg2\"/></a:solidFill>"
                                      out)
                        (write-string "<a:noFill/>" out))
                    (write-string "</a:tcPr></a:tc>" out)
                    (incf cx w)
                    (incf col n))))
              (write-string "</a:tr>" out))
      (write-string "</a:tbl></a:graphicData></a:graphic></p:graphicFrame>" out))))

;;; --- diagrams ------------------------------------------------------------------------

(defun flow-box-faces (shapes)
  "The face rectangle of each flow box, as (FACE I PAD OUTER-X OUTER-Y OUTER-W OUTER-H RX)."
  (let ((faces nil))
    (dolist (box shapes)
      (when (eq (car box) :box)
        (destructuring-bind (bx by bw bh i pad) (cdr box)
          (dolist (s shapes)
            (when (and (eq (car s) :rect) (equal (sixth s) "dg-f"))
              (destructuring-bind (rx ry rw rh class rrad) (cdr s)
                (declare (ignore class))
                (when (and (>= rx bx) (>= ry by)
                           (<= (+ rx rw) (+ bx bw)) (<= (+ ry rh) (+ by bh)))
                  (push (list (list rx ry rw rh) i pad bx by bw bh rrad)
                        faces))))))))
    faces))

(defun emit-flow-face (front b size ax ay sw sh l top-l pad bw i rx)
  "The face of a flow box at AX, AY, with its label as the shape's text: one shape in
PowerPoint, not a box with a separate text box over it."
  (let* ((px (dg-pad-x size))
         (lh (dg-lh size))
         (mar-l (max 0 (- px l)))
         (mar-t (max 0 (- pad top-l)))
         (inner (- bw (* 2 px)))
         (run (nth i (diagram-runs b)))
         (lines (and run (run-line-boxes run size inner lh 0 0 "center")))
         (id (next-shape)))
      (format front
              "<p:sp><p:nvSpPr><p:cNvPr id=\"~d\" name=\"Flow box ~d\"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr><p:spPr><a:xfrm><a:off x=\"~d\" y=\"~d\"/><a:ext cx=\"~d\" cy=\"~d\"/></a:xfrm>"
              id id (emu ax) (emu ay) (emu sw) (emu sh))
      (if (and rx (> rx 0) (> sw 0) (> sh 0))
          (format front
                  "<a:prstGeom prst=\"roundRect\"><a:avLst><a:gd name=\"adj\" fmla=\"val ~d\"/></a:avLst></a:prstGeom>"
                  (min 50000 (round (* rx 100000) (min sw sh))))
          (write-string "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom>" front))
      (emit-fill front '(:scheme "bg2"))
      (write-string "<a:ln><a:noFill/></a:ln></p:spPr><p:txBody>" front)
      (format front
              "<a:bodyPr lIns=\"~d\" tIns=\"~d\" rIns=\"~d\" bIns=\"0\" wrap=\"none\" anchor=\"t\"><a:noAutofit/></a:bodyPr><a:lstStyle/>"
              (emu mar-l) (emu mar-t) (emu mar-l))
      (emit-paragraph-props front size lh "ctr")
      (when lines
        (let ((first t))
          (dolist (line lines)
            (destructuring-bind (start end top lx lw) line
              (declare (ignore top lx lw))
              (unless first (write-string "<a:br/>" front))
              (setf first nil)
              (table-cell-runs front run start end size)))))
      (emit-end-para front size)
      (write-string "</p:txBody></p:sp>" front)))

(defun emit-diagram (out front rec)
  (destructuring-bind (kind b s x y) rec
    (declare (ignore kind))
    (let ((d (diagram-drawing b))
          (faces (flow-box-faces (drawing-shapes (diagram-drawing b)))))
      (dolist (shape (drawing-shapes d))
        (case (car shape)
          (:rect
           (destructuring-bind (sx sy sw sh class rx) (cdr shape)
             (let ((face (find (list sx sy sw sh) faces :key #'first :test #'equal)))
               (if face
                   (destructuring-bind (box i pad bx by bw bh rrad) face
                     (declare (ignore box))
                     (emit-flow-face front b s (+ x sx) (+ y sy) sw sh
                                     (- sx bx) (- sy by) pad bw i rrad))
                   (sp-rect front (+ x sx) (+ y sy) sw sh (diagram-fill class)
                            :rx rx)))))
          (:poly
           (let ((class (second shape)))
             (sp-poly front
                      (loop for (px py) on (cddr shape) by #'cddr
                            collect (cons (+ x px) (+ y py)))
                      (diagram-fill class))))
          (:dot
           (destructuring-bind (cx cy r &optional (class "dg-e")) (cdr shape)
             (let ((points
                     (loop for k from 0 below 24
                           for a = (/ (* 2 pi k) 24)
                           collect (cons (round (+ x cx (* r (cos a))))
                                         (round (+ y cy (* r (sin a))))))))
               (sp-poly front points (diagram-fill class)))))
          (:slice
           (destructuring-bind (class box r ri large x1 y1 x2 y2 x3 y3 x4 y4)
               (cdr shape)
             (sp-slice front class (list (+ x (first box)) (+ y (second box))
                                         (+ x (third box)) (+ y (fourth box)))
                       r ri large (+ x x1) (+ y y1) (+ x x2) (+ y y2)
                       (+ x x3) (+ y y3) (+ x x4) (+ y y4))))
          ((:value :tick :atom) nil)))
    ;; The text a shape carries is set after its drawing.
    (dolist (shape (drawing-shapes d))
      (case (car shape)
        (:value
         (destructuring-bind (vx vy vw vh text align i) (cdr shape)
           (declare (ignore i))
           (sp-text out (+ x vx) (+ y vy) vw vh s (dg-lh s)
                    (cond ((equal align "right") "r")
                          ((equal align "center") "ctr")
                          (t "l"))
                    (lambda (o)
                      (emit-paragraph-props o s (dg-lh s) nil)
                      (emit-run o text nil s :body)
                      (emit-end-para o s))
                    :anchor "ctr")))
        (:tick
         (destructuring-bind (vx vy vw vh text align) (cdr shape)
           (sp-text out (+ x vx) (+ y vy) vw vh s (dg-lh s)
                    (cond ((equal align "right") "r")
                          ((equal align "center") "ctr")
                          (t "l"))
                    (lambda (o)
                      (emit-paragraph-props o s (dg-lh s) nil)
                      (emit-run o text '(:mono t) s :tick)
                      (emit-end-para o s))
                    :anchor "ctr")))
        (:atom
         (destructuring-bind (vx vy vw vh text) (cdr shape)
           (sp-text out (+ x vx) (+ y vy) vw vh s (dg-lh s) nil
                    (lambda (o)
                      (emit-paragraph-props o s (dg-lh s) nil)
                      (emit-run o text '(:mono t) s :body)
                      (emit-end-para o s))
                    :anchor "ctr"))))))))

;;; --- formulas ------------------------------------------------------------------------

(defun emit-formula-shape (out x y w h fill)
  (sp-rect out x y (max 1 w) (max 1 h) fill))

(defun emit-formula (out f fx baseline s)
  "The glyphs and rules of F, whose left edge is FX and baseline BASELINE, at S px."
  (let* ((box (formula-box f))
         (items (mbox-items box)))
    (dolist (item items)
      (case (car item)
        (:text
         (destructuring-bind (x y w k style text) (cdr item)
           (let* ((size (max 1 (ceiling (* s k) 1000)))
                  (cx (+ fx (floor (* (+ x (floor w 2)) s) 1000)))
                  (by (+ baseline (floor (* y s) 1000)))
                  (measured (max size (run-full-width (make-text-run (list text)) size)))
                  (top (- by (floor (* *pptx-ascent* size) 1000))))
             (sp-text out (- cx measured) top (* 2 measured)
                      (max 1 (floor (* size 13) 10)) size
                      (max 1 (floor (* size 14) 10)) nil
                      (lambda (o)
                        (emit-paragraph-props o size (max 1 (floor (* size 14) 10)) "ctr")
                        (let ((attrs (list :mono)))
                          (declare (ignore attrs))
                          (emit-run o text
                                    (case style (:it '(:italic t)) (:bold '(:bold t)) (t nil))
                                    size :body))
                        (emit-end-para o size))))))
        (:rule
         (destructuring-bind (x y w h) (cdr item)
           (emit-formula-shape out (+ fx (floor (* x s) 1000))
                               (+ baseline (floor (* y s) 1000))
                               (max 1 (floor (* w s) 1000))
                               (max 1 (floor (* h s) 1000))
                               '(:scheme "tx1"))))
        (:poly
         (let ((points (loop for (x y) on (cdr item) by #'cddr
                             collect (cons (+ fx (floor (* x s) 1000))
                                           (+ baseline (floor (* y s) 1000))))))
           (sp-poly out points '(:scheme "tx1"))))
        (:stretch
         (destructuring-bind (x y w k glyph sy center) (cdr item)
           (declare (ignore sy))
           (let* ((size (max 1 (ceiling (* s k) 1000)))
                  (cx (+ fx (floor (* (+ x (floor w 2)) s) 1000)))
                  (by (+ baseline (floor (* (+ y center) s) 1000)))
                  (measured (max size (run-full-width (make-text-run (list glyph)) size)))
                  (top (- by (floor (* *pptx-ascent* size) 1000))))
             (sp-text out (- cx measured) top (* 2 measured)
                      (max 1 (floor (* size 13) 10)) size
                      (max 1 (floor (* size 14) 10)) nil
                      (lambda (o)
                        (emit-paragraph-props o size (max 1 (floor (* size 14) 10)) "ctr")
                        (emit-run o glyph nil size :body)
                        (emit-end-para o size))))))))))

(defun emit-formula-record (out rec)
  (destructuring-bind (kind f x y w h s) rec
    (declare (ignore kind w h))
    (let ((baseline (+ y (floor (* (mbox-ascent (formula-box f)) s) 1000))))
      (emit-formula out f x baseline s))))

;;; --- pictures ------------------------------------------------------------------------

(defun picture-xml (out x y w h part rel crop alt)
  (let ((id (next-shape)))
    (format out
            "<p:pic><p:nvPicPr><p:cNvPr id=\"~d\" name=\"Picture ~d\" descr=\""
            id id)
    (write-xml-attr (or alt "") out)
    (format out
            "\"/><p:cNvPicPr><a:picLocks noChangeAspect=\"1\"/></p:cNvPicPr><p:nvPr/></p:nvPicPr><p:blipFill><a:blip r:embed=\"~a\"/>"
            rel)
    (when crop
      (destructuring-bind (l t0 r b) crop
        (format out "<a:srcRect l=\"~d\" t=\"~d\" r=\"~d\" b=\"~d\"/>" l t0 r b)))
    (write-string "<a:stretch><a:fillRect/></a:stretch></p:blipFill><p:spPr>" out)
    (format out
            "<a:xfrm><a:off x=\"~d\" y=\"~d\"/><a:ext cx=\"~d\" cy=\"~d\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></p:spPr></p:pic>"
            (emu x) (emu y) (emu w) (emu h))))

(defun emit-picture (out x y w h figure mode)
  "PICTURE of FIGURE in the box X, Y, W x H: whole (MODE :contain) or covering it
(:cover), cropped from the source's edges."
  (let* ((src (if (figure-video figure)
                  (or (figure-poster figure) (figure-src figure))
                  (figure-src figure)))
         (part (and src (media-part src))))
    (cond
      ((null part)
       (sp-rect out x y w h '(:scheme "bg2") :outline '(:scheme "tx2"))
       (sp-text out (+ x 8) (+ y 8) (max 1 (- w 16)) (max 1 (- h 16)) 24 32 nil
                (lambda (o)
                  (emit-paragraph-props o 24 32 nil)
                  (emit-run o (or (figure-alt figure) src "image") nil 24 :body)
                  (emit-end-para o 24))
                :anchor "ctr"))
      (t
       (let* ((name (first part)) (iw (third part)) (ih (fourth part))
              (rel (add-rel *image-rel* (media-target part))))
         (if (or (null iw) (null ih) (zerop iw) (zerop ih))
             (picture-xml out x y w h part rel nil (figure-alt figure))
             (if (eq mode :cover)
                 (let* ((src-aspect (/ iw ih)) (box-aspect (/ w h)))
                   (if (> src-aspect box-aspect)
                       (let* ((keep (/ ih box-aspect))
                              (crop (round (* (- iw keep) 100000) iw))
                              (half (floor crop 2)))
                         (picture-xml out x y w h part rel (list half 0 half 0) (figure-alt figure)))
                       (let* ((keep (/ iw box-aspect))
                              (crop (round (* (- ih keep) 100000) ih))
                              (half (floor crop 2)))
                         (picture-xml out x y w h part rel (list 0 half 0 half) (figure-alt figure)))))
                 (let* ((scale (min (/ w iw) (/ h ih)))
                        (pw (round (* iw scale)))
                        (ph (round (* ih scale))))
                   (picture-xml out (+ x (floor (- w pw) 2)) (+ y (floor (- h ph) 2))
                                pw ph part rel nil (figure-alt figure))))))))))

(defun emit-video-placeholder (out x y w h figure)
  (sp-rect out x y w h '(:scheme "bg2") :outline '(:scheme "tx2"))
  (sp-text out (+ x 8) (+ y 8) (max 1 (- w 16)) (max 1 (- h 16)) 28 36 nil
           (lambda (o)
             (emit-paragraph-props o 28 36 nil)
             (emit-run o (or (figure-alt figure) (figure-src figure))
                       (if (url-p (figure-src figure))
                           (list :link (figure-src figure)) nil)
                       28 :body)
             (emit-end-para o 28))
           :anchor "ctr"))

(defun emit-frame (out x y w h figure)
  (sp-rect out x y w h nil :outline '(:scheme "accent1"))
  (sp-text out (+ x 8) (+ y 8) (max 1 (- w 16)) (max 1 (- h 16)) 28 36 nil
           (lambda (o)
             (emit-paragraph-props o 28 36 nil)
             (emit-run o (or (figure-alt figure) (figure-src figure))
                       (if (url-p (figure-src figure))
                           (list :link (figure-src figure)) nil)
                       28 :body)
             (emit-end-para o 28))
           :anchor "ctr"))

;;; --- callouts ------------------------------------------------------------------------

(defun emit-callout (out c)
  (let* ((x (callout-x c)) (y (callout-y c)) (w (callout-width c)) (h (callout-height c))
         (s (callout-size c)) (tail (callout-tail c)) (side (callout-side c)))
    ;; The tail, under the bubble: the accent triangle, then its fill.
    (flet ((tri (pts fill)
             (sp-poly out pts fill)))
      (cond
        ((equal side "below")
         (tri (list (cons (+ x tail) (- y 35)) (cons (+ x tail 18) y)
                    (cons (+ x tail -18) y)) '(:scheme "accent1"))
         (tri (list (cons (+ x tail) (- y 29)) (cons (+ x tail 16) y)
                    (cons (+ x tail -16) y)) '(:scheme "bg2")))
        ((equal side "above")
         (tri (list (cons (+ x tail -18) (+ y h)) (cons (+ x tail 18) (+ y h))
                    (cons (+ x tail) (+ y h 35))) '(:scheme "accent1"))
         (tri (list (cons (+ x tail -16) (+ y h)) (cons (+ x tail 16) (+ y h))
                    (cons (+ x tail) (+ y h 29))) '(:scheme "bg2")))
        ((equal side "right")
         (tri (list (cons (- x 35) (+ y tail)) (cons x (+ y tail -18))
                    (cons x (+ y tail 18))) '(:scheme "accent1"))
         (tri (list (cons (- x 29) (+ y tail)) (cons x (+ y tail -16))
                    (cons x (+ y tail 16))) '(:scheme "bg2")))
        (t
         (tri (list (cons (+ x w) (+ y tail -18)) (cons (+ x w 35) (+ y tail))
                    (cons (+ x w) (+ y tail 18))) '(:scheme "accent1"))
         (tri (list (cons (+ x w) (+ y tail -16)) (cons (+ x w 29) (+ y tail))
                    (cons (+ x w) (+ y tail 16))) '(:scheme "bg2")))))
    (sp-rect out x y w h '(:scheme "bg2") :rx 16 :outline '(:scheme "accent1"))
    (let* ((pad-x (say-pad-x s)) (pad-y (say-pad-y s))
           (iw (- w (* 2 pad-x))))
      (dolist (line (run-line-boxes (callout-run c) s iw (say-lh s)
                                    (+ x pad-x) (+ y pad-y)))
        (destructuring-bind (start end top lx lw) line
          (declare (ignore lw))
          (when (< start end)
            (emit-line-box out (callout-run c) start end s (say-lh s) lx top :body
                           (+ x pad-x iw))))))))

;;; --- the title, section and statement layouts ----------------------------------------

(defun emit-centered-lines (out run s width lh top align)
  (dolist (line (run-line-boxes run s width lh *margin-x* top align))
    (destructuring-bind (start end t0 lx lw) line
      (declare (ignore lw))
      (when (< start end)
        (emit-line-box out run start end s lh lx t0 :body
                       (+ *margin-x* width))))))

(defun emit-title-slide (out front deck slide)
  (let* ((fit (slide-fit slide))
         (s (fit-stmt-size fit))
         (cw (content-width deck))
         (center (+ *margin-x* (floor cw 2))))
    (let ((title-top nil))
      (dolist (row (title-rows deck slide s))
        (destructuring-bind (kind top h w &optional run start end) row
          (case kind
            (:logo
             (let* ((figure (deck-logo deck))
                    (src (and figure (figure-src figure)))
                    (part (and src (media-part src)))
                    (lw (if (and part (> (fourth part) 0))
                            (round (* *logo-height* (third part)) (fourth part))
                            *logo-height*)))
               (if part
                   (picture-xml front (- center (floor lw 2)) top lw *logo-height*
                                part
                                (add-rel *image-rel* (media-target part))
                                nil (figure-alt (deck-logo deck)))
                   (sp-rect front (- center (floor lw 2)) top lw *logo-height*
                            '(:scheme "bg2")))))
            (:title
             (unless title-top (setf title-top top)))
            (:meta
             (let ((lw (milli-px->px (run-span-width run start end *meta-size*))))
               (emit-line-box out run start end *meta-size* h
                              (- center (floor lw 2)) top :meta
                              (+ *margin-x* cw)))))))
      (when title-top
        (emit-lines-paragraph out (slide-title-run slide) s (statement-lh s)
                              *margin-x* title-top (content-width deck) "ctr"
                              :body)))
    (when (fit-qr fit)
      (destructuring-bind (qx qy q qw align) (fit-qr fit)
        (let* ((right (equal align "right"))
               (x (if right (- (+ qx qw) q) qx)))
          (emit-qr front out deck x qy q)
          (sp-text out qx (+ qy q *qr-caption-gap*) (max q qw) (qr-lh)
                   *qr-caption-size* (qr-lh) (if right "r" "l")
                   (lambda (o)
                     (emit-paragraph-props o *qr-caption-size* (qr-lh)
                                           (if right "r" "l"))
                     (emit-run o (deck-url deck)
                               (list :link (deck-url deck)) *qr-caption-size* :body)
                     (emit-end-para o *qr-caption-size*))))))))

(defun emit-qr (front out deck x y size)
  "The QR code of the deck's URL: a white quiet zone and one rectangle per dark run."
  (declare (ignore out))
  (multiple-value-bind (m n) (qr-matrix (utf-8-octets (deck-url deck)))
    (when m
      (let* ((side (+ n 8))
             (unit (max 1 (floor size side))))
        (sp-rect front x y size size '(:rgb "FFFFFF"))
        (dotimes (row n)
          (let ((col 0))
            (loop while (< col n)
                  do
                    (if (zerop (svref m (+ col (* row n))))
                        (incf col)
                        (let ((start col))
                          (loop while (and (< col n)
                                           (= 1 (svref m (+ col (* row n)))))
                                do (incf col))
                          (sp-rect front
                                   (+ x (* (+ start 4) unit))
                                   (+ y (* (+ row 4) unit))
                                   (* (- col start) unit) unit
                                   '(:rgb "000000")))))))))))

(defun emit-section-slide (out deck slide)
  (let* ((fit (slide-fit slide))
         (s (fit-stmt-size fit))
         (run (slide-title-run slide))
         (width (fit-width fit))
         (lines (run-lines run s width))
         (top (centered-box (* lines (statement-lh s)))))
    (emit-lines-paragraph out run s (statement-lh s) (fit-left fit) top width
                          "ctr" :body)))

(defun emit-statement-slide (out deck slide)
  (let* ((fit (slide-fit slide))
         (s (fit-stmt-size fit))
         (p (car (slide-blocks slide)))
         (run (para-run p))
         (width (fit-width fit))
         (available (- (safe-height) (source-height slide width)))
         (lines (run-lines run s width))
         (top (centered-box (* lines (statement-lh s)) available)))
    (if (run-formulas run)
        (emit-centered-lines out run s width (statement-lh s) top "center")
        (emit-lines-paragraph out run s (statement-lh s) (fit-left fit) top width
                              "ctr" :body))))

;;; --- slides --------------------------------------------------------------------------

(defun emit-lines-paragraph (out run s lh x y width align role)
  "RUN as one text box WIDTH wide at X, Y, its lines as the model wraps them. A title
keeps the whole text region, so a wide title or a wider font does not wrap it."
  (let ((lines (run-line-boxes run s width lh x y align)))
    (sp-text out x y width (* (length lines) lh) s lh align
             (lambda (o)
               (emit-paragraph-props o s lh align)
               (let ((first t))
                 (dolist (line lines)
                   (destructuring-bind (start end top lx lw) line
                     (declare (ignore top lx lw))
                     (unless first (write-string "<a:br/>" o))
                     (setf first nil)
                     (emit-run-text o run start end s role))))
               (emit-end-para o s)))))

(defun emit-slide-title (out deck slide)
  "The title of a content slide, as one box across the text region."
  (let* ((fit (slide-fit slide))
         (s (fit-title-size fit))
         (run (slide-title-run slide)))
    (when (and run s)
      (emit-lines-paragraph out run s (title-lh s) (fit-left fit) *margin-top*
                            (fit-width fit) nil :body))))

(defun emit-body-records (out front deck slide)
  (let ((records (fit-geometry (slide-fit slide)))
        (groups nil))
    (dolist (rec records)
      (case (car rec)
        (:text
         (let ((role (ninth rec)))
           (cond ((eq role :cell))
                 ((eq role :flow-label))
                 ((eq role :item)
                  (let ((group (getf (nth 9 rec) :group)))
                    (unless (member group groups :test #'eq)
                      (push group groups)
                      (emit-list-group
                       out
                       (remove-if-not
                        (lambda (r)
                          (and (eq (car r) :text) (eq (ninth r) :item)
                               (eq (getf (nth 9 r) :group) group)))
                        records)))))
                 ((member role '(:body :lead :quote :cite :caption :edge-label
                                 :dg-label)
                          :test #'eq)
                  (emit-paragraph-record out rec))
                 (t (emit-text-record out rec)))))
        (:marker nil)
      (:code (emit-code out front rec))
      (:table-row nil)
      (:table (emit-native-table out rec))
      (:picture
       (destructuring-bind (kind b x y r btm) rec
         (declare (ignore kind))
         (cond ((and (figure-video b) (null (figure-poster b)))
                (emit-video-placeholder front x y (- r x) (- btm y) b))
               ((figure-frame b)
                (emit-frame front x y (- r x) (- btm y) b))
               (t (emit-picture front x y (- r x) (- btm y) b :contain)))))
      (:diagram (emit-diagram out front rec))
      (:formula (emit-formula-record out rec))
      (t nil)))))

(defun emit-bleed (out front deck slide)
  (destructuring-bind (figure left width) (fit-bleed (slide-fit slide))
    (cond ((and (figure-video figure) (null (figure-poster figure)))
           (emit-video-placeholder front left 0 width *canvas-height* figure))
          ((figure-frame figure)
           (emit-frame front left 0 width *canvas-height* figure))
          (t
           (emit-picture front left 0 width *canvas-height* figure
                         (if (figure-fit figure) :contain :cover))))))

(defun pptx-slide-xml (deck slide notes-index)
  "The slide's XML and rels. NOTES-INDEX is the slide's notes part, or nil."
  (let ((*pptx-slide-rels* nil)
        (*shape-id* 1)
        (out (make-string-output-stream))
        (front (make-string-output-stream)))
    (push (list "rId1" *layout-rel* "../slideLayouts/slideLayout1.xml")
          *pptx-slide-rels*)
    (let ((fit (slide-fit slide))
          (layout (fit-layout (slide-fit slide))))
      (cond ((eq (slide-kind slide) :title)
             (emit-title-slide out front deck slide))
            ((equal layout "full")
             (emit-bleed out front deck slide))
            ((equal layout "section")
             (emit-section-slide out deck slide))
            ((equal layout "statement")
             (emit-statement-slide out deck slide))
            (t
             (when (fit-bleed fit) (emit-bleed out front deck slide))
             (emit-slide-title out deck slide)
             (emit-body-records out front deck slide)))
      (dolist (c (slide-callouts slide)) (emit-callout out c)))
    (when notes-index
      (add-rel *notes-slide-rel*
               (format nil "../notesSlides/notesSlide~d.xml" notes-index)))
    (format out "</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sld>")
    (values
     (concatenate 'string
                  "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                  "<p:sld " *pm-ns* "><p:cSld>"
                  *sp-tree-baseline*
                  (get-output-stream-string front)
                  (get-output-stream-string out))
     (reverse *pptx-slide-rels*))))

(defun notes-slide-xml (text slide-index)
  (let ((*pptx-slide-rels* nil) (*shape-id* 1)
        (body (make-string-output-stream)))
    (add-rel *notes-master-rel* "../notesMasters/notesMaster1.xml")
    (add-rel *slide-rel* (format nil "../slides/slide~d.xml" slide-index))
    (dolist (line (split-lines text))
      (emit-paragraph-props body 12 18 nil)
      (emit-run body line nil 12 :body)
      (emit-end-para body 12))
    (values
     (with-output-to-string (out)
       (write-string "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" out)
       (format out "<p:notes ~a><p:cSld>" *pm-ns*)
       (write-string *sp-tree-baseline* out)
       (sp-text out 48 48 864 576 12 18 nil
                (lambda (o) (write-string (get-output-stream-string body) o)))
       (write-string "</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:notes>"
                     out))
     (reverse *pptx-slide-rels*))))

;;; --- package -------------------------------------------------------------------------

(defun pptx-content-types (slides notes media)
  (content-types-xml slides notes
                     (remove-duplicates (mapcar #'third media) :test #'equal)))

(defun pptx-octets (deck)
  "The PPTX package of DECK. Images are embedded; colors and fonts are theme references."
  (let ((*pptx-media* nil) (*pptx-media-bytes* nil) (*pptx-media-count* 0)
        (slides nil) (notes nil) (note-of (make-hash-table)) (note-count 0))
    (dolist (slide (deck-slides deck))
      (when (slide-notes slide)
        (incf note-count)
        (setf (gethash (slide-number slide) note-of) note-count)
        (push (list (slide-notes slide) (slide-number slide)) notes)))
    (setf notes (nreverse notes))
    (dolist (slide (deck-slides deck))
      (multiple-value-bind (xml rels)
          (pptx-slide-xml deck slide (gethash (slide-number slide) note-of))
        (push (list xml rels) slides)))
    (setf slides (nreverse slides))
    (let* ((slide-count (length slides))
           (notes-parts
             (loop for (text index) in notes
                   collect (multiple-value-bind (xml rels)
                               (notes-slide-xml text index)
                             (list xml rels))))
           (parts nil)
           (tail nil))
      (labels ((add (name content)
                 (let ((cell (list (cons name content))))
                   (if tail (setf (cdr tail) cell) (setf parts cell))
                   (setf tail cell))))
        (add "[Content_Types].xml"
             (pptx-content-types slide-count (length notes-parts) *pptx-media*))
        (add "_rels/.rels"
             (rels-xml
              (list (list "rId1"
                          "http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument"
                          "ppt/presentation.xml")
                    (list "rId2"
                          "http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties"
                          "docProps/core.xml")
                    (list "rId3"
                          "http://schemas.openxmlformats.org/officeDocument/2006/relationships/extended-properties"
                          "docProps/app.xml"))))
        (add "docProps/core.xml" (core-xml deck))
        (add "docProps/app.xml" (app-xml))
        (add "ppt/presentation.xml"
             (presentation-xml slide-count (canvas-width deck) *canvas-height*))
        (add "ppt/_rels/presentation.xml.rels"
             (rels-xml
              (append
               (list (list "rId1" *master-rel* "slideMasters/slideMaster1.xml")
                     (list "rId2" *notes-master-rel* "notesMasters/notesMaster1.xml"))
               (loop for i from 1 to slide-count
                     collect (list (format nil "rId~d" (+ 2 i)) *slide-rel*
                                   (format nil "slides/slide~d.xml" i))))))
        (add "ppt/slideMasters/slideMaster1.xml" (slide-master-xml))
        (add "ppt/slideMasters/_rels/slideMaster1.xml.rels"
             (rels-xml
              (list (list "rId1" *layout-rel* "../slideLayouts/slideLayout1.xml")
                    (list "rId2" *theme-rel* "../theme/theme1.xml"))))
        (add "ppt/slideLayouts/slideLayout1.xml" (slide-layout-xml))
        (add "ppt/slideLayouts/_rels/slideLayout1.xml.rels"
             (rels-xml (list (list "rId1" *master-rel*
                                   "../slideMasters/slideMaster1.xml"))))
        (add "ppt/theme/theme1.xml" (theme-xml))
        (add "ppt/notesMasters/notesMaster1.xml" (notes-master-xml))
        (add "ppt/notesMasters/_rels/notesMaster1.xml.rels"
             (rels-xml (list (list "rId1" *theme-rel* "../theme/theme1.xml"))))
        (loop for (xml rels) in slides
              for i from 1
              do (add (format nil "ppt/slides/slide~d.xml" i) xml)
                 (add (format nil "ppt/slides/_rels/slide~d.xml.rels" i)
                      (rels-xml rels)))
        (loop for (xml rels) in notes-parts
              for i from 1
              do (add (format nil "ppt/notesSlides/notesSlide~d.xml" i) xml)
                 (add (format nil "ppt/notesSlides/_rels/notesSlide~d.xml.rels" i)
                      (rels-xml rels)))
        (dolist (entry *pptx-media-bytes*)
          (add (car entry) (cdr entry)))
        (zip-octets parts)))))
