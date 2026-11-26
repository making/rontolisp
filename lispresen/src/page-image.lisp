;;;; page-image.lisp -- the title slide drawn as the PNG a link preview shows.
;;;;
;;;; The image is the title slide at 5/8 of the canvas, 1200 x 675 px: the 1080 px canvas
;;;; height maps to 675, so a canvas px is 10 sub-pixels, and a 4:3 slide stands in the
;;;; middle of the same frame. Every row is where TITLE-ROWS puts it, the lines broken
;;;; where the fit solver broke them, each set on the advances of the deck's TrueType
;;;; font and centered as the browser centers it; the QR code is in the corner the solver
;;;; chose. The midnight theme's arcs are drawn; its palt is not, a layout feature.

(in-package :lispresen)

(defun canvas-sub (n)
  "Canvas px N in the page image's sub-pixels."
  (* n 10))

(defun drawn-page-image-p (deck)
  "Whether lispresen draws the deck's :page-image: a relative path beside the page."
  (let ((image (deck-page-image deck)))
    (and image (deck-page-url deck) (not (search "://" image))
         (not (and (plusp (length image)) (char= (char image 0) #\/))))))

(defun load-font (path)
  (let ((octets (and *read-file* (funcall *read-file* path))))
    (if octets
        (parse-font path octets)
        (progn (report :error (format nil "cannot read font ~a" path)) nil))))

(defun baseline (font top lh size)
  "The baseline of a line box LH sub-pixels tall from TOP, the font's ascender and
descender centered in it, SIZE sub-pixels to the em."
  (let ((units (font-units font)))
    (+ top (floor (+ (* lh units)
                     (* (- (font-ascender font) (font-descender font)) size))
                  (* 2 units)))))

(defun trimmed (text)
  (string-trim " " text))

(defun draw-page-image (deck theme logo-src)
  "The PNG of DECK's title slide in THEME, LOGO-SRC the logo's path as the deck wrote
it; nil, reported, when it cannot be drawn."
  (let* ((slide (find :title (deck-slides deck) :key #'slide-kind))
         (fonts (mapcar #'load-font (deck-page-image-font deck)))
         (regular (first fonts))
         (bold (or (second fonts) regular))
         (missing nil))
    (when (or (null slide) (notevery #'identity fonts))
      (return-from draw-page-image nil))
    (let* ((fit (slide-fit slide))
           (s (fit-stmt-size fit))
           (cw (canvas-width deck))
           (ox (floor (- (* *page-image-width* *sub*) (canvas-sub cw)) 2))
           (left (+ ox (canvas-sub *margin-x*)))
           (content (canvas-sub (content-width deck)))
           (midnight (equal (car theme) "MIDNIGHT"))
           (img (make-image *page-image-width* *page-image-height*
                            (parse-color (theme-color theme "bg")))))
      (labels ((x (n) (+ ox (canvas-sub n)))
               (color (name) (parse-color (theme-color theme name)))
               (glyphs (font text)
                 (let ((gs (text-glyphs font text)))
                   ;; MISSING holds (FONT CHARACTER...) for each font lacking some.
                   (loop for g in gs
                         for c across text
                         when (= g 0)
                         do (let ((entry (assoc font missing)))
                              (unless entry
                                (setf entry (list font))
                                (setf missing (append missing (list entry))))
                              (unless (member c (cdr entry))
                                (setf (cdr entry) (append (cdr entry) (list c))))))
                   gs))
               (line (font text top lh size rgb align at spacing &optional underline)
                 ;; One line of TEXT in a box LH tall from TOP: centered in the content
                 ;; width, or from AT (:left) or to AT (:right).
                 (let* ((gs (glyphs font text))
                        (units (font-units font))
                        (w (floor (* (text-advance font gs spacing) size) units))
                        (x0 (case align
                              (:center (+ left (floor (- content w) 2)))
                              (:left at)
                              (t (- at w))))
                        (y (baseline font top lh size)))
                   (draw-text img font gs x0 y size rgb spacing)
                   (when underline
                     (fill-rect img x0
                                (- y (floor (* (font-underline-position font) size) units))
                                w
                                (max *sub* (floor (* (font-underline-thickness font) size)
                                                  units))
                                rgb)))))
        (when midnight
          ;; The arcs: the left and right edges of 1000 x 2400 ellipses centered 540 px
          ;; in, 3 px wide at mid-height (theme.lisp).
          (dolist (side '((540 64 192) (-540 192 320)))
            (destructuring-bind (cx from to) side
              (let ((cx (x (if (> cx 0) cx (+ cw cx)))) (cy (canvas-sub 540)))
                (fill-edges img
                            (polygon-edges
                             (append (arc-points cx cy 5000 12000 from to 1)
                                     (arc-points cx cy 4970 12000 to from -1)))
                            (color "accent"))))))
        (dolist (row (title-rows deck slide s))
          (destructuring-bind (kind top h w &optional run start end) row
            (declare (ignore w))
            (let ((top (canvas-sub top)) (lh (canvas-sub h)))
              (case kind
                (:logo (draw-logo img logo-src left top content))
                (:title
                 (line bold (trimmed (subseq (run-text run) start end)) top lh
                       (canvas-sub s) (color "fg") :center 0
                       (if midnight (- (floor (font-units bold) 100)) 0)))
                (:meta
                 (line regular (trimmed (subseq (run-text run) start end)) top lh
                       (canvas-sub *meta-size*) (color "muted") :center 0 0))))))
        (when (fit-qr fit)
          (destructuring-bind (qx qy q w align) (fit-qr fit)
            (let* ((right (equal align "right"))
                   (code-x (x (if right (- (+ qx w) q) qx)))
                   (top (canvas-sub qy)))
              (draw-qr img (deck-url deck) code-x top (canvas-sub q))
              (line regular (deck-url deck) (+ top (canvas-sub (+ q *qr-caption-gap*)))
                    (canvas-sub (qr-lh)) (canvas-sub *qr-caption-size*) (color "muted")
                    (if right :right :left) (x (if right (+ qx w) qx)) 0 t)))))
      (dolist (entry missing)
        (report :warning
                (format nil "font ~a has no glyph for ~{~a~}; the page image shows a box"
                        (font-name (car entry)) (cdr entry))))
      (png-octets *page-image-width* *page-image-height* (image-palette img)
                  (image-pixels img)))))

(defun draw-qr (img text left top size)
  "The QR code of TEXT, SIZE sub-pixels square from (LEFT . TOP): dark modules on white
with the quiet zone, each module edge on a pixel edge as crispEdges draws it."
  (multiple-value-bind (m n) (qr-matrix (utf-8-octets text))
    (when m
      (let ((side (+ n 8)))
        (flet ((edge (origin k)
                 (* *sub* (floor (+ origin (floor (* k size) side) (floor *sub* 2)) *sub*))))
          (fill-rect img (edge left 0) (edge top 0) (- (edge left side) (edge left 0))
                     (- (edge top side) (edge top 0)) #xFFFFFF)
          (dotimes (y n)
            (let ((x 0))
              (loop while (< x n)
                    do (if (zerop (svref m (+ x (* y n))))
                           (incf x)
                           (let ((start x))
                             (loop while (and (< x n) (= 1 (svref m (+ x (* y n)))))
                                   do (incf x))
                             (fill-rect img (edge left (+ start 4)) (edge top (+ y 4))
                                        (- (edge left (+ x 4)) (edge left (+ start 4)))
                                        (- (edge top (+ y 5)) (edge top (+ y 4)))
                                        0)))))))))))

(defun draw-logo (img src left top width)
  "The logo SRC, 160 px tall (no wider than WIDTH sub-pixels) and centered in WIDTH
from LEFT, its row starting at TOP; an SVG is drawn, anything else reported."
  (let ((octets (and *read-file* (not (url-p src)) (funcall *read-file* src)))
        (box (canvas-sub *logo-height*)))
    (cond ((not (string-equal (or (extension src) "") "svg"))
           (report :warning
                   (format nil "the page image draws an SVG logo only; ~a is left out" src)))
          ((null octets)
           (report :warning
                   (format nil "cannot read logo ~a; the page image leaves it out" src)))
          (t
           (let* ((text (or (utf-8-string octets) ""))
                  (view (svg-view text)))
             (if (stringp view)
                 (report :warning
                         (format nil "the page image leaves out logo ~a: it uses ~a" src
                                 view))
                 (let ((vw (third view)) (vh (fourth view)))
                   (let* ((w (floor (* box vw) vh)) (h box))
                     (when (> w width)
                       (setf h (floor (* width vh) vw) w width))
                     (draw-svg img text (+ left (floor (- width w) 2))
                               (+ top (floor (- box h) 2)) h)))))))))
