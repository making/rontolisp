;;;; train.lisp -- offline trainer.  Loads common.lisp + prototypes.lisp
;;;; (see gen.sh) and runs on the interpreter or JVM.  It trains a 576-20-46 MLP
;;;; on multi-font, affine-augmented copies of the glyphs and prints the learned
;;;; weights to STDOUT as a single (defparameter *weights* (quote ...)) form.
;;;; Progress is emitted as Lisp comment lines (';;'), which the reader ignores,
;;;; so the stdout capture is a valid weights.lisp.
;;;;
;;;; The (load ...) paths are resolved relative to THIS file, so the trainer can
;;;; be run or compiled from any directory.  On the compilers a top-level literal
;;;; (load ...) is a compile-time include, so the loaded defuns compile natively.

(load "common.lisp")
(load "prototypes.lisp")

(defparameter *width* 24)
(defparameter *height* 24)
(defparameter *input* 576)              ; *width* * *height*
(defparameter *hidden* 20)              ; capped so infer.lisp loads on the JVM
                                        ; backend: a v50 class with no stackmaps
                                        ; verifies on JDK 25 only up to ~12.8k
                                        ; baked float constants, so the whole net
                                        ; (623*hidden + 46 weights at 24x24/46)
                                        ; must stay under that.  WASM and the
                                        ; interpreter have no such cap.
(defparameter *nclasses* 46)
(defparameter *epochs* 600)
(defparameter *lr* 0.5)

;;; ---------------------------------------------------------------------------
;;; Network construction (as in examples/ml/mlp.lisp)
;;; ---------------------------------------------------------------------------

(defun random-weight () (- (random 1.0) 0.5))

(defun random-vector (n)
  (let ((v (make-array n :initial-element 0.0)))
    (dotimes (i n) (setf (aref v i) (random-weight)))
    v))

(defun random-matrix (rows cols)
  (let ((m (make-array (list rows cols) :initial-element 0.0)))
    (dotimes (i rows)
      (dotimes (j cols) (setf (aref m i j) (random-weight))))
    m))

(defun init-layers (sizes)
  (if (null (rest sizes))
      nil
      (cons (list (random-matrix (second sizes) (first sizes))
                  (random-vector (second sizes)))
            (init-layers (rest sizes)))))

;;; ---------------------------------------------------------------------------
;;; Backprop over a single example (sigmoid + squared error), unchanged from
;;; mlp.lisp -- it already reads layer widths off the bias/input vectors, so a
;;; 46-unit one-hot output works with no edits.
;;; ---------------------------------------------------------------------------

(defun output-delta (aL y)
  (let* ((n (length aL))
         (delta (make-array n :initial-element 0.0)))
    (dotimes (i n)
      (setf (aref delta i)
            (* (- (aref aL i) (aref y i)) (aref aL i) (- 1.0 (aref aL i)))))
    delta))

(defun train-example (layers x y lr)
  (let* ((acts (forward-all layers x))
         (rev-layers (reverse layers))
         (rev-acts (reverse acts))
         (delta (output-delta (first rev-acts) y))
         (cur (rest rev-acts)))
    (dolist (layer rev-layers)
      (let* ((w (layer-w layer)) (b (layer-b layer))
             (a-prev (first cur))
             (rows (length b)) (cols (length a-prev))
             (new-delta (make-array cols :initial-element 0.0)))
        (dotimes (j cols)
          (let ((s 0.0))
            (dotimes (i rows) (incf s (* (aref w i j) (aref delta i))))
            (setf (aref new-delta j)
                  (* s (aref a-prev j) (- 1.0 (aref a-prev j))))))
        (dotimes (i rows)
          (dotimes (j cols)
            (decf (aref w i j) (* lr (aref delta i) (aref a-prev j))))
          (decf (aref b i) (* lr (aref delta i))))
        (setq delta new-delta)
        (setq cur (rest cur))))
    layers))

;;; ---------------------------------------------------------------------------
;;; Data augmentation.  A drawn stroke varies in position, thickness, and edge
;;; raggedness far more than a single font template, so we widen the training
;;; set along all three: translate (shift-image), thicken (dilate -- a hand
;;; stroke is usually heavier than the font after the browser downsamples it),
;;; and flip random pixels (add-noise).  All operate on a flat 24x24 list.
;;; ---------------------------------------------------------------------------

(defun shift-image (flat dx dy)
  (let ((src (list->vector flat))
        (dst (make-array *input* :initial-element 0.0)))
    (dotimes (y *height*)
      (dotimes (x *width*)
        (let ((sx (- x dx)) (sy (- y dy)))
          (when (and (>= sx 0) (< sx *width*) (>= sy 0) (< sy *height*))
            (setf (aref dst (+ (* y *width*) x))
                  (aref src (+ (* sy *width*) sx)))))))
    (let ((acc nil))
      (dotimes (i *input*) (setq acc (cons (aref dst (- *input* 1 i)) acc)))
      acc)))

(defun add-noise (flat k)               ; flip k random pixels
  (let ((v (list->vector flat)))
    (dotimes (n k)
      (let ((idx (random *input*)))
        (setf (aref v idx) (if (> (aref v idx) 0.5) 0.0 1.0))))
    (let ((acc nil))
      (dotimes (i *input*) (setq acc (cons (aref v (- *input* 1 i)) acc)))
      acc)))

(defun flat-of (v)                       ; rank-1 array -> flat list (row-major)
  (let ((acc nil))
    (dotimes (i *input*) (setq acc (cons (aref v (- *input* 1 i)) acc)))
    acc))

(defun ink-at (v x y)                    ; 1 if (x,y) is in range and ink, else 0
  (if (and (>= x 0) (< x *width*) (>= y 0) (< y *height*))
      (if (> (aref v (+ (* y *width*) x)) 0.5) 1 0)
      0))

;; Thicken every stroke by one pixel (set a cell if it or a 4-neighbor is ink).
;; Drawn strokes are usually heavier than the thin font template, so training on
;; dilated copies is the single biggest help for real handwriting.
(defun dilate (flat)
  (let ((src (list->vector flat))
        (dst (make-array *input* :initial-element 0.0)))
    (dotimes (y *height*)
      (dotimes (x *width*)
        (when (> (+ (ink-at src x y) (ink-at src (- x 1) y) (ink-at src (+ x 1) y)
                    (ink-at src x (- y 1)) (ink-at src x (+ y 1)))
                 0)
          (setf (aref dst (+ (* y *width*) x)) 1.0))))
    (flat-of dst)))

;; Affine warp (rotation by ANG radians, then horizontal SHEAR) about the grid
;; centre, nearest-neighbour.  Handwriting strokes are rarely upright, so warped
;; copies teach the net to tolerate slant/rotation.  We inverse-map each dst
;; pixel to its source: M = Shear(k)*Rot(a) has det 1, so M^-1 = [[c, s-k*c],
;; [-s, c+k*s]] (c=cos a, s=sin a).
(defun warp-image (flat ang shear)
  (let* ((src (list->vector flat))
         (dst (make-array *input* :initial-element 0.0))
         (cx (round (/ *width* 2.0))) (cy (round (/ *height* 2.0)))
         (c (cos ang)) (s (sin ang))
         (m00 c) (m01 (- s (* shear c)))
         (m10 (- 0.0 s)) (m11 (+ c (* shear s))))
    (dotimes (y *height*)
      (dotimes (x *width*)
        (let* ((rx (- x cx)) (ry (- y cy))
               (ix (+ (round (+ (* m00 rx) (* m01 ry))) cx))
               (iy (+ (round (+ (* m10 rx) (* m11 ry))) cy)))
          (when (and (>= ix 0) (< ix *width*) (>= iy 0) (< iy *height*)
                     (> (aref src (+ (* iy *width*) ix)) 0.5))
            (setf (aref dst (+ (* y *width*) x)) 1.0)))))
    (flat-of dst)))

(defun one-hot (class)
  (let ((v (make-array *nclasses* :initial-element 0.0)))
    (setf (aref v class) 1.0)
    v))

;; Build (input-vector . one-hot-vector) examples.  *glyphs* groups the per-font
;; glyphs of each class (see prototypes.lisp); we train on every font variant so
;; the net learns stroke-shape variation (hooks, brush vs round).  Per variant we
;; add a small shift set, thickened copies, and a noisy/extra-thick copy -- the
;; fonts already supply most of the diversity, so the per-variant set stays light.
(defun build-dataset ()
  (let ((data nil) (class 0))
    (dolist (variants *glyphs*)
      (let ((target (one-hot class)))
        (dolist (glyph variants)
          (let ((base (glyph->list glyph)))
            ;; centred: base, thickened, extra-thick, noisy
            (setq data (cons (list (list->vector base) target) data))
            (setq data (cons (list (list->vector (dilate base)) target) data))
            (setq data (cons (list (list->vector (dilate (dilate base))) target) data))
            (setq data (cons (list (list->vector (add-noise base 5)) target) data))
            ;; small shifts (the browser centres the glyph, so a few suffice)
            (dolist (shift (list (list -1 0) (list 1 0) (list 0 -1) (list 0 1)))
              (let ((img (shift-image base (first shift) (second shift))))
                (setq data (cons (list (list->vector img) target) data))))
            ;; rotation / shear for slanted handwriting, each also thickened
            (dolist (w (list (list 0.20 0.0) (list -0.20 0.0)
                             (list 0.0 0.22) (list 0.0 -0.22)))
              (let ((img (warp-image base (first w) (second w))))
                (setq data (cons (list (list->vector img) target) data))
                (setq data (cons (list (list->vector (dilate img)) target) data)))))))
      (setq class (+ class 1)))
    data))

;;; ---------------------------------------------------------------------------
;;; Accuracy / training loop
;;; ---------------------------------------------------------------------------

(defun dataset-accuracy (layers data)
  (let ((correct 0))
    (dolist (ex data)
      (when (= (argmax (predict layers (first ex)))
               (argmax (second ex)))
        (setq correct (+ correct 1))))
    (/ (float correct) (length data))))

(defun train (layers data epochs lr)
  (let ((e 0))
    (while (< e epochs)
      (dolist (ex data)
        (train-example layers (first ex) (second ex) lr))
      (when (zerop (mod e 300))
        (format t ";; epoch ~a  train-acc ~a~%" e (dataset-accuracy layers data)))
      (setq e (+ e 1))))
  layers)

;;; ---------------------------------------------------------------------------
;;; Serialization.  We emit weights.lisp as Lisp SOURCE so that *weights* ends
;;; up shaped exactly like infer.lisp expects: a list of layers, each
;;; (rows cols flat-W flat-b).  Floats print at full round-trip precision.
;;;
;;; Why chunk it?  The JVM backend compiles each top-level form into a method,
;;; and a method's bytecode is capped at 64 KB.  A single flat list of tens of
;;; thousands of float literals blows that cap.  So each flat vector is split into small
;;; (defun gN () (list ...)) chunk functions (each well under the cap, and its
;;; own method), and *weights* reassembles them with append.  The interpreter
;;; and WASM have no such cap, but the chunked form runs there unchanged.
;;; ---------------------------------------------------------------------------

(defun mat->flat (m rows cols)
  (let ((acc nil))
    (dotimes (i rows) (dotimes (j cols) (setq acc (cons (aref m i j) acc))))
    (reverse acc)))

(defun vec->flat (v n)
  (let ((acc nil))
    (dotimes (i n) (setq acc (cons (aref v i) acc)))
    (reverse acc)))

(defparameter *chunk* 200)              ; floats per chunk function
(defparameter *gid* 0)                  ; running chunk-function counter

(defun take-n (lst n)
  (if (or (null lst) (<= n 0)) nil
      (cons (first lst) (take-n (rest lst) (- n 1)))))

(defun drop-n (lst n)
  (if (or (null lst) (<= n 0)) lst
      (drop-n (rest lst) (- n 1))))

;; Print "(defun gN () (list <=*chunk* floats>))"; return the name string.
(defun emit-chunk (nums)
  (let ((name (format nil "g~a" *gid*)))
    (setq *gid* (+ *gid* 1))
    (princ "(defun ") (princ name) (princ " () (list")
    (dolist (x nums) (princ " ") (princ x))
    (princ "))") (terpri)
    name))

;; Emit chunk defuns covering FLAT; return the list of chunk-name strings.
(defun emit-flat (flat)
  (if (null flat)
      nil
      (cons (emit-chunk (take-n flat *chunk*))
            (emit-flat (drop-n flat *chunk*)))))

;; Print "(append (g0) (g1) ...)" reassembling a flat vector from its chunks.
(defun princ-append (names)
  (princ "(append")
  (dolist (n names) (princ " (") (princ n) (princ ")"))
  (princ ")"))

;; sizes = (in h ... out); layer k has rows = sizes[k+1], cols = sizes[k].
(defun emit-weights (layers sizes)
  (let ((specs nil) (k 0))
    ;; Pass 1: emit every chunk defun, collecting per-layer (rows cols wnames bnames).
    (dolist (layer layers)
      (let* ((cols (nth k sizes))
             (rows (nth (+ k 1) sizes))
             (w (layer-w layer)) (b (layer-b layer))
             (wnames (emit-flat (mat->flat w rows cols)))
             (bnames (emit-flat (vec->flat b rows))))
        (setq specs (cons (list rows cols wnames bnames) specs))
        (setq k (+ k 1))))
    (setq specs (reverse specs))
    ;; Pass 2: the (defparameter *weights* ...) that assembles the layers.
    (princ "(defparameter *weights* (list")
    (dolist (s specs)
      (princ " (list ") (princ (first s)) (princ " ") (princ (second s)) (princ " ")
      (princ-append (third s)) (princ " ")
      (princ-append (fourth s)) (princ ")"))
    (princ "))") (terpri)))

(format t ";; hiragana MLP trainer: ~a-~a-~a~%" *input* *hidden* *nclasses*)
(defparameter *data* (build-dataset))
(format t ";; dataset size ~a~%" (length *data*))
(defparameter *net* (init-layers (list *input* *hidden* *nclasses*)))
(setq *net* (train *net* *data* *epochs* *lr*))
(format t ";; final train-acc ~a~%" (dataset-accuracy *net* *data*))

(emit-weights *net* (list *input* *hidden* *nclasses*))
