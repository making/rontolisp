;;;; truetype.lisp -- glyph outlines and advances from a TrueType font file.
;;;;
;;;; Reads the tables the page image needs from the bytes of a .ttf: head, hhea, hmtx,
;;;; maxp, loca, cmap (formats 4 and 12), post and glyf, composite glyphs included. A
;;;; font with CFF outlines (OpenType 'OTTO') or a collection is refused: its cubic
;;;; charstrings would be a second outline format. Hinting and layout features (kerning,
;;;; palt) are not applied; text is set on the advances.

(in-package :lispresen)

(defstruct font
  name
  octets
  tables
  units
  long-loca
  ascender
  descender
  metrics
  glyphs
  cmap
  cmap-format
  underline-position
  underline-thickness)

(defun u8 (v i) (aref v i))
(defun u16 (v i) (+ (ash (aref v i) 8) (aref v (1+ i))))
(defun s16 (v i) (let ((n (u16 v i))) (if (>= n 32768) (- n 65536) n)))
(defun u32 (v i) (+ (ash (u16 v i) 16) (u16 v (+ i 2))))

(defun font-table (font tag)
  (cdr (assoc tag (font-tables font) :test #'string=)))

(defun parse-font (name octets)
  "The font in OCTETS, or nil, reported, when it is no TrueType font."
  (flet ((refuse ()
           (report :error (format nil
                                  "font ~a is not a TrueType font with glyf outlines"
                                  name))
           (return-from parse-font nil)))
    (when (or (null octets) (< (length octets) 12)) (refuse))
    (let ((version (u32 octets 0)))
      (unless (or (= version #x00010000) (= version #x74727565)) (refuse)))
    (let* ((n (u16 octets 4))
           (tables
            (loop for k below n
                  for at = (+ 12 (* 16 k))
                  while (<= (+ at 16) (length octets))
                  collect (cons (map 'string #'code-char (subseq octets at (+ at 4)))
                                (u32 octets (+ at 8)))))
           (font (make-font :name name :octets octets :tables tables)))
      (unless (every (lambda (tag) (assoc tag tables :test #'string=))
                     '("head" "hhea" "hmtx" "maxp" "loca" "glyf" "cmap"))
        (refuse))
      (let ((head (font-table font "head"))
            (hhea (font-table font "hhea"))
            (post (font-table font "post")))
        (setf (font-units font) (u16 octets (+ head 18))
              (font-long-loca font) (= 1 (s16 octets (+ head 50)))
              (font-ascender font) (s16 octets (+ hhea 4))
              (font-descender font) (- (s16 octets (+ hhea 6)))
              (font-metrics font) (u16 octets (+ hhea 34))
              (font-glyphs font) (u16 octets (+ (font-table font "maxp") 4))
              (font-underline-position font) (if post (s16 octets (+ post 8)) -100)
              (font-underline-thickness font) (if post (s16 octets (+ post 10)) 50)))
      (let ((cmap (font-table font "cmap")) (best nil) (rank 0))
        ;; Prefer a Unicode full-repertoire subtable (format 12) to a BMP one (format 4).
        (dotimes (k (u16 octets (+ cmap 2)))
          (let* ((at (+ cmap 4 (* 8 k)))
                 (platform (u16 octets at))
                 (sub (+ cmap (u32 octets (+ at 4))))
                 (format (u16 octets sub))
                 (r (cond ((not (or (= platform 0) (= platform 3))) 0)
                          ((= format 12) 2)
                          ((= format 4) 1)
                          (t 0))))
            (when (> r rank) (setf best sub rank r))))
        (unless best (refuse))
        (setf (font-cmap font) best (font-cmap-format font) (u16 octets best)))
      font)))

(defun glyph-index (font code)
  "The glyph of the character CODE, 0 (the missing glyph) when the font has none."
  (let ((v (font-octets font)) (at (font-cmap font)))
    (if (= (font-cmap-format font) 12)
        (dotimes (k (u32 v (+ at 12)) 0)
          (let ((g (+ at 16 (* 12 k))))
            (when (<= (u32 v g) code (u32 v (+ g 4)))
              (return (+ (u32 v (+ g 8)) (- code (u32 v g)))))))
        (let* ((segs (floor (u16 v (+ at 6)) 2))
               (ends (+ at 14))
               (starts (+ ends (* 2 segs) 2))
               (deltas (+ starts (* 2 segs)))
               (ranges (+ deltas (* 2 segs))))
          (if (> code 65535)
              0
              (dotimes (k segs 0)
                (when (>= (u16 v (+ ends (* 2 k))) code)
                  (let ((start (u16 v (+ starts (* 2 k))))
                        (delta (u16 v (+ deltas (* 2 k))))
                        (range (u16 v (+ ranges (* 2 k)))))
                    (return
                      (cond ((< code start) 0)
                            ((= range 0) (logand (+ code delta) 65535))
                            (t
                             (let ((g (u16 v (+ ranges (* 2 k) range
                                                (* 2 (- code start))))))
                               (if (= g 0) 0 (logand (+ g delta) 65535))))))))))))))

(defun glyph-advance (font g)
  "The advance width of glyph G in font units."
  (let ((hmtx (font-table font "hmtx")) (n (font-metrics font)))
    (u16 (font-octets font) (+ hmtx (* 4 (min g (1- n)))))))

(defun glyph-location (font g)
  "The offset and length of glyph G in glyf."
  (let ((v (font-octets font)) (loca (font-table font "loca")))
    (flet ((at (k)
             (if (font-long-loca font)
                 (u32 v (+ loca (* 4 k)))
                 (* 2 (u16 v (+ loca (* 2 k)))))))
      (values (+ (font-table font "glyf") (at g)) (- (at (1+ g)) (at g))))))

(defun glyph-contours (font g &optional (depth 0))
  "The outline of glyph G: a list of contours, each a list of (X Y ON-CURVE) in font
units, y upward."
  (when (or (>= g (font-glyphs font)) (> depth 8))
    (return-from glyph-contours nil))
  (multiple-value-bind (at size) (glyph-location font g)
    (when (<= size 0) (return-from glyph-contours nil))
    (let* ((v (font-octets font)) (n (s16 v at)))
      (if (>= n 0)
          (simple-glyph v at n)
          (composite-glyph font v (+ at 10) depth)))))

(defun simple-glyph (v at n)
  (let* ((ends (loop for k below n collect (u16 v (+ at 10 (* 2 k)))))
         (count (if ends (1+ (car (last ends))) 0))
         (p (+ at 10 (* 2 n)))
         (flags (make-array count))
         (xs (make-array count))
         (ys (make-array count)))
    (setf p (+ p 2 (u16 v p)))
    (let ((k 0))
      (loop while (< k count)
            do (let ((f (u8 v p)))
                 (incf p)
                 (setf (svref flags k) f)
                 (incf k)
                 (when (logbitp 3 f)
                   (let ((r (u8 v p)))
                     (incf p)
                     (dotimes (j r)
                       (when (< k count) (setf (svref flags k) f) (incf k))))))))
    (flet ((coords (out short same)
             (let ((c 0))
               (dotimes (k count)
                 (let ((f (svref flags k)))
                   (cond ((logbitp short f)
                          (incf c (if (logbitp same f) (u8 v p) (- (u8 v p))))
                          (incf p))
                         ((not (logbitp same f))
                          (incf c (s16 v p))
                          (incf p 2)))
                   (setf (svref out k) c))))))
      (coords xs 1 4)
      (coords ys 2 5))
    (let ((start 0))
      (loop for e in ends
            collect (loop for k from start to e
                          collect (list (svref xs k) (svref ys k)
                                        (logbitp 0 (svref flags k))))
            do (setf start (1+ e))))))

(defun f2dot14 (v i) (s16 v i))

(defun composite-glyph (font v p depth)
  "The contours of the components from P, each moved by its offset and scaled by its
2.14 matrix (16384 being 1)."
  (let ((contours nil) (more t))
    (loop while more
          do (let* ((flags (u16 v p))
                    (g (u16 v (+ p 2)))
                    (words (logbitp 0 flags))
                    (dx (if words (s16 v (+ p 4)) (let ((b (u8 v (+ p 4)))) (if (>= b 128) (- b 256) b))))
                    (dy (if words (s16 v (+ p 6)) (let ((b (u8 v (+ p 5)))) (if (>= b 128) (- b 256) b))))
                    (a 16384) (b 0) (c 0) (d 16384))
               (setf p (+ p 4 (if words 4 2)))
               ;; Point-matched placement (no ARGS_ARE_XY_VALUES) is taken as no offset.
               (unless (logbitp 1 flags) (setf dx 0 dy 0))
               (cond ((logbitp 3 flags)
                      (setf a (f2dot14 v p) d a)
                      (incf p 2))
                     ((logbitp 6 flags)
                      (setf a (f2dot14 v p) d (f2dot14 v (+ p 2)))
                      (incf p 4))
                     ((logbitp 7 flags)
                      (setf a (f2dot14 v p) b (f2dot14 v (+ p 2))
                            c (f2dot14 v (+ p 4)) d (f2dot14 v (+ p 6)))
                      (incf p 8)))
               (dolist (contour (glyph-contours font g (1+ depth)))
                 (push (mapcar (lambda (pt)
                                 (destructuring-bind (x y on) pt
                                   (list (+ dx (floor (+ (* a x) (* c y)) 16384))
                                         (+ dy (floor (+ (* b x) (* d y)) 16384))
                                         on)))
                               contour)
                       contours))
               (setf more (logbitp 5 flags))))
    (nreverse contours)))
