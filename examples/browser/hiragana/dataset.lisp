;;;; dataset.lisp -- the training data: real handwriting (Kuzushiji-49) mixed
;;;; with augmented copies of the synthetic multi-font glyphs.
;;;;
;;;; Why both.  The synthetic glyphs (prototypes.lisp, rendered from four real
;;;; Japanese fonts by glyphgen/GlyphGen.java) are MODERN letterforms -- exactly
;;;; what somebody drawing in the browser writes -- but they are type, not
;;;; handwriting: augmentation only ever wobbles them rigidly, so a net trained
;;;; on them alone never learns that a stroke may be hooked, disconnected or
;;;; differently proportioned -- the ceiling the previous version of this demo
;;;; hit, and the reason it read a freehand あ as さ.  K49
;;;; is real handwritten kana with all of that variation, but it is cursive
;;;; (kuzushiji) and its shapes drift from the modern ones.  Trained together,
;;;; the real data supplies the handwriting variation and the synthetic set pins
;;;; the modern letterform.
;;;;
;;;; Both halves arrive in the SAME representation the browser produces: a 24x24
;;;; binary bitmap, ink-bbox-cropped, centred with a 1px margin, binarized at
;;;; 0.35 (index.html's toBitmap; GlyphGen.render and tools/k49/prepare-k49.py
;;;; reproduce it pixel for pixel).
;;;;
;;;; Everything below hands the trainer packed linalg arrays: the images as a
;;;; rank-4 (N 1 24 24) batch (the shape the convolution wants) and the labels as
;;;; a rank-1 vector of class indices.

(load "net.lisp")
(load "prototypes.lisp")

;;; ---------------------------------------------------------------------------
;;; A seeded RNG for the augmentation.
;;;
;;; linalg:seed / linalg:rand exist, but they hand back arrays; the augmenters
;;; want one number at a time, so this is the same i31-safe LCG the ml examples
;;; use.  Seeded, so a rerun of the trainer sees the same augmented set.
;;; ---------------------------------------------------------------------------

(defparameter *rng* 12345)

(defun rnd () ; uniform in [0, 1)
  (setq *rng* (mod (+ (* *rng* 1103515) 12345) 2097152))
  (/ *rng* 2097152.0))

(defun rnd-int (n) (floor (* (rnd) n)))

(defun rnd-range (lo hi) (+ lo (* (rnd) (- hi lo))))

;;; ---------------------------------------------------------------------------
;;; Image ops.  An image is a packed length-576 double vector of 0.0 / 1.0
;;; (row-major); every op returns a fresh one.
;;; ---------------------------------------------------------------------------

(defun blank-image () (linalg:zeros *pixels*))

(defun ink-at (v x y) ; 1.0 if (x, y) is in range and ink
  (if (and (>= x 0) (< x *grid*) (>= y 0) (< y *grid*))
      (aref v (+ (* y *grid*) x))
      0.0))

(defun glyph->image (rows) ; one prototypes.lisp glyph -> an image
  (let ((v (blank-image)) (i 0))
    (dolist (e (glyph->list rows))
      (setf (aref v i) e)
      (setq i (+ i 1)))
    v))

(defun shift-image (v dx dy)
  (let ((dst (blank-image)))
    (dotimes (y *grid*)
      (dotimes (x *grid*)
        (setf (aref dst (+ (* y *grid*) x)) (ink-at v (- x dx) (- y dy)))))
    dst))

;; Thicken every stroke by one pixel (4-neighbourhood dilation).  A drawn stroke
;; is heavier than the thin font outline after the browser downsamples it.
(defun dilate (v)
  (let ((dst (blank-image)))
    (dotimes (y *grid*)
      (dotimes (x *grid*)
        (when (> (+ (ink-at v x y) (ink-at v (- x 1) y) (ink-at v (+ x 1) y)
                    (ink-at v x (- y 1)) (ink-at v x (+ y 1))) 0.5)
          (setf (aref dst (+ (* y *grid*) x)) 1.0))))
    dst))

;; Affine warp about the grid centre: rotate by ANG radians, then shear
;; horizontally by SHEAR, nearest-neighbour, inverse-mapped.  M = Shear(k)*Rot(a)
;; has det 1, so M^-1 = [[c, s-k*c], [-s, c+k*s]] with c = cos a, s = sin a.
;; Handwriting is rarely upright.
(defun warp-image (v ang shear)
  (let* ((dst (blank-image))
         (c (cos ang))
         (s (sin ang))
         (m00 c)
         (m01 (- s (* shear c)))
         (m10 (- 0.0 s))
         (m11 (+ c (* shear s)))
         (cc (floor *grid* 2)))
    (dotimes (y *grid*)
      (dotimes (x *grid*)
        (let* ((rx (- x cc))
               (ry (- y cc))
               (fx (+ (* m00 rx) (* m01 ry)))
               (fy (+ (* m10 rx) (* m11 ry)))
               (ix (+ (round fx) cc))
               (iy (+ (round fy) cc)))
          (setf (aref dst (+ (* y *grid*) x)) (ink-at v ix iy)))))
    dst))

;; Flip K random pixels: the browser's downsample leaves ragged edges.
(defun add-noise (v k)
  (let ((dst (linalg:add v 0))) ; a copy, same width
    (dotimes (n k)
      (let ((i (rnd-int *pixels*)))
        (setf (aref dst i) (if (> (aref dst i) 0.5) 0.0 1.0))))
    dst))

;;; ---------------------------------------------------------------------------
;;; The synthetic half: every (class, font) glyph, augmented.
;;; ---------------------------------------------------------------------------

(defparameter *variants-per-glyph* 8) ; random variants on top of the fixed ones

(defun augment-glyph (base)
  ;; BASE plus a fixed spine (thickened / extra-thick / shifted) and
  ;; *variants-per-glyph* random rotate+shear+shift+thicken+noise copies.
  (let ((out
         (list base (dilate base) (dilate (dilate base)) (shift-image base -1 0)
               (shift-image base 1 0) (shift-image base 0 -1)
               (shift-image base 0 1))))
    (dotimes (i *variants-per-glyph*)
      (let* ((img
              (warp-image base (rnd-range -0.25 0.25) (rnd-range -0.25 0.25)))
             (img (shift-image img (- (rnd-int 3) 1) (- (rnd-int 3) 1)))
             (img (if (> (rnd) 0.35) (dilate img) img))
             (img (if (> (rnd) 0.5) (add-noise img (rnd-int 6)) img)))
        (setq out (cons img out))))
    out))

(defun synthetic-samples ()
  ;; ((image . class) ...) over every class x font x augmentation.
  (let ((acc nil) (class 0))
    (dolist (variants *glyphs*)
      (dolist (glyph variants)
        (dolist (img (augment-glyph (glyph->image glyph)))
          (setq acc (cons (cons img class) acc))))
      (setq class (+ class 1)))
    acc))

;;; ---------------------------------------------------------------------------
;;; The real half: the HKB1 files written by tools/k49/prepare-k49.py.
;;;
;;;     magic "HKB1" | count u32 be | grid u8 | COUNT x (label u8, grid*grid bytes)
;;;
;;; Read strictly front-to-back with read-byte (there is no seek), so LIMIT just
;;; stops early -- the file is pre-shuffled, so a prefix is a fair sample.
;;; ---------------------------------------------------------------------------

(defun k49-load (path limit)
  ;; -> (images labels): ((n 1 24 24) packed batch, rank-1 label vector).
  (with-open-file (s path :element-type '(unsigned-byte 8))
    (unless (and (= (read-byte s) 72) (= (read-byte s) 75) (= (read-byte s) 66)
                 (= (read-byte s) 49))
      (error "not an HKB1 file (run tools/k49/prepare-k49.py first)"))
    (let* ((count (%read-be32 s))
           (grid (read-byte s))
           (pixels (* grid grid))
           (n (min limit count))
           (x
            (make-array (list n 1 grid grid)
                        :element-type 'double-float
                        :initial-element 0.0))
           (lab
            (make-array n :element-type 'double-float :initial-element 0.0)))
      (dotimes (i n)
        (setf (aref lab i) (read-byte s))
        (let ((base (* i pixels)))
          (dotimes (k pixels)
            (setf (row-major-aref x (+ base k)) (* 1.0 (read-byte s))))))
      (list x lab))))

;;; ---------------------------------------------------------------------------
;;; Assembling one batch tensor out of both halves.
;;; ---------------------------------------------------------------------------

(defun samples->batch (samples)
  ;; ((image . class) ...) -> ((n 1 24 24) batch, label vector).
  (let* ((n (length samples))
         (x
          (make-array (list n 1 *grid* *grid*)
                      :element-type 'double-float
                      :initial-element 0.0))
         (lab (make-array n :element-type 'double-float :initial-element 0.0))
         (i 0))
    (dolist (sample samples)
      (let ((img (car sample)) (base (* i *pixels*)))
        (dotimes (k *pixels*) (setf (row-major-aref x (+ base k)) (aref img k)))
        (setf (aref lab i) (cdr sample))
        (setq i (+ i 1))))
    (list x lab)))

(defun concat-batches (a b)
  ;; Two (images labels) pairs -> one.  Rank-4 batches concatenate along axis 0,
  ;; which linalg has no operator for (take-rows selects, it does not join), so
  ;; this is a straight row-major copy of both slabs.
  (let* ((xa (first a))
         (la (second a))
         (xb (first b))
         (lb (second b))
         (na (car (linalg:shape xa)))
         (nb (car (linalg:shape xb)))
         (n (+ na nb))
         (x
          (make-array (list n 1 *grid* *grid*)
                      :element-type 'double-float
                      :initial-element 0.0))
         (lab (make-array n :element-type 'double-float :initial-element 0.0)))
    (dotimes (k (linalg:size xa))
      (setf (row-major-aref x k) (row-major-aref xa k)))
    (dotimes (k (linalg:size xb))
      (setf (row-major-aref x (+ (linalg:size xa) k)) (row-major-aref xb k)))
    (dotimes (i na) (setf (aref lab i) (aref la i)))
    (dotimes (i nb) (setf (aref lab (+ na i)) (aref lb i)))
    (list x lab)))

(defun shuffle-batch (b)
  ;; A seeded permutation of the rows (linalg:permutation), so the synthetic and
  ;; real halves interleave instead of arriving in two blocks.
  (let ((p (linalg:permutation (car (linalg:shape (first b))))))
    (list (linalg:take-rows (first b) p) (linalg:take-rows (second b) p))))
