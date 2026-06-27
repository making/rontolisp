;;;; train-main.lisp -- offline trainer.  Concatenated after common.lisp and
;;;; prototypes.lisp (see gen.sh) and run on the interpreter or JVM.  It trains
;;;; a 144-HIDDEN-5 MLP on shifted/noisy copies of the reference glyphs and
;;;; prints the learned weights to STDOUT as a single (defparameter *weights*
;;;; (quote ...)) form.  Progress is emitted as Lisp comment lines (';;'), which
;;;; the reader ignores, so the stdout capture is a valid weights.lisp.

(defparameter *width* 16)
(defparameter *height* 16)
(defparameter *input* 256)              ; *width* * *height*
(defparameter *hidden* 32)
(defparameter *nclasses* 5)
(defparameter *epochs* 1500)
(defparameter *lr* 0.5)

;;; ---------------------------------------------------------------------------
;;; Network construction (as in examples/mlp.lisp)
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
;;; 5-unit one-hot output works with no edits.
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
;;; Data augmentation: translate a flat 12x12 image by (dx, dy), dropping
;;; pixels that fall off the grid, then optionally flip a few random pixels.
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

(defun one-hot (class)
  (let ((v (make-array *nclasses* :initial-element 0.0)))
    (setf (aref v class) 1.0)
    v))

;; Build (input-vector . one-hot-vector) examples: every (dx,dy) in [-1,1]^2
;; plus two noisy copies of each, for each class.
(defun build-dataset ()
  (let ((data nil) (class 0))
    (dolist (glyph *glyphs*)
      (let ((base (glyph->list glyph))
            (target (one-hot class)))
        (dolist (dy (list -1 0 1))
          (dolist (dx (list -1 0 1))
            (let ((img (shift-image base dx dy)))
              (setq data (cons (list (list->vector img) target) data))
              (setq data (cons (list (list->vector (add-noise img 3)) target) data))
              (setq data (cons (list (list->vector (add-noise img 6)) target) data)))))
        (setq class (+ class 1))))
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
;;; up shaped exactly like infer-main.lisp expects: a list of layers, each
;;; (rows cols flat-W flat-b).  Floats print at full round-trip precision.
;;;
;;; Why chunk it?  The JVM backend compiles each top-level form into a method,
;;; and a method's bytecode is capped at 64 KB.  A single flat list of ~3.6k
;;; float literals blows that cap.  So each flat vector is split into small
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
