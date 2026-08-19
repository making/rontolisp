;; chapter02/section3.lisp -- notebook section 2.3, ported.
;;
;; The four pieces a Transformer block is made of, each checked the way the
;; notebook checks it: the embedding table (2.3.1), the sinusoidal positional
;; encoding and the dot products between its rows (2.3.2), the position-wise
;; feed-forward network (2.3.3), the residual connection that makes the identity
;; learnable (2.3.4), and layer normalization (2.3.5).
;;
;; The notebook's heat map and its two line plots become the numbers behind
;; them: the encoding's first rows, and the theoretical dot product against the
;; measured one.
;;
;;   rontolisp chapter02/section3.lisp

(load "../transformer/utils.lisp")

(linalg:seed 42)

;; --- 2.3.1: the embedding table ---------------------------------------------

(defparameter *vocabulary-size* 100)

(defparameter *embedding* (torch:embedding *vocabulary-size* 512))

(format t "embedding table:  ~a~%"
        (torch:shape (torch:field *embedding* :weight)))
(format t "embedded tokens:  ~a~%"
        (torch:shape (torch:forward *embedding* '(1 2 3))))
(format t "embedded batch:   ~a~%"
        (torch:shape
         (torch:forward *embedding* (torch:pad-sequence '((1 2 3) (4 5))))))

;; --- 2.3.2: the sinusoidal positional encoding ------------------------------

(defparameter *pe-d-model* 128)

(defparameter *pe-length* 100)

(defparameter *pe*
  (linalg:squeeze (sinusoidal-position-encoding *pe-d-model* *pe-length*)
                  :axis 0))

(format t "encoding shape:   ~a~%" (linalg:shape *pe*))
(format t "first four dimensions of the first four positions:~%")
(dotimes (pos 4)
  (format t "  pos ~a:" pos)
  (dotimes (j 4) (format t " ~,4f" (aref *pe* pos j)))
  (format t "~%"))

;; Every row has the same length, which is what makes the dot product below a
;; function of the position DIFFERENCE alone.
(format t "row norms equal:  ~a~%"
        (if (< (- (linalg:amax (linalg:sum (linalg:square *pe*) :axis 1))
                  (linalg:amin (linalg:sum (linalg:square *pe*) :axis 1)))
               1.0e-6)
            "yes"
            "no"))

;; The notebook plots pe . pe^T and compares it against the closed form
;;   dot(pos) = sum over d of cos(pos / 10000^(2d / d_model)).
;; Here the two are compared numerically instead, over every pair of rows that
;; many positions apart.
(defun theoretical-dot (offset d-model)
  (let ((total 0.0))
    (do ((d 0 (+ d 1)))
        ((>= d (/ d-model 2)) total)
      (setq total
            (+ total (cos (/ offset (expt 10000.0 (/ (* 2.0 d) d-model)))))))))

(defparameter *dot-products* (linalg:matmul *pe* (linalg:transpose *pe*)))

(format t "dot product by position difference (theory vs measured):~%")
(dolist (offset '(0 1 2 5 20 50))
  (let ((worst 0.0) (theory (theoretical-dot offset *pe-d-model*)))
    (dotimes (i (- *pe-length* offset))
      (let ((diff (abs (- (aref *dot-products* i (+ i offset)) theory))))
        (when (> diff worst) (setq worst diff))))
    (format t "  offset ~2,'0d: ~7,2f  max deviation < 1e-6: ~a~%" offset theory
            (if (< worst 1.0e-6) "yes" "no"))))

;; --- 2.3.3: the position-wise feed-forward network --------------------------

(defparameter *ff-d-model* 64)

(defparameter *feed-forward*
  (torch:sequential (torch:linear *ff-d-model* (* 4 *ff-d-model*))
                    (function torch:relu)
                    (torch:linear (* 4 *ff-d-model*) *ff-d-model*)))

(defparameter *ff-input* (torch:tensor (linalg:randn (list 1 10 *ff-d-model*))))

(format t "feed-forward in:  ~a~%" (torch:shape *ff-input*))
(format t "feed-forward out: ~a~%"
        (torch:shape (torch:forward *feed-forward* *ff-input*)))

;; --- 2.3.4: learning the identity, with and without a skip connection --------
;; The notebook trains a two-layer FFN and the same block wrapped in x + f(x) to
;; reproduce their own input. The residual one starts near zero error because
;; the identity is already its default behaviour. Shapes are scaled down from
;; the notebook's 10000 x 10 / 100 epochs so the plain interpreter finishes in
;; seconds; the shape of the two curves is the point, not their length.

(defun ffn (d-model d-ff)
  (torch:sequential (torch:linear d-model d-ff) (function torch:relu)
                    (torch:linear d-ff d-model)))

(defun skip-connection-forward (self x)
  (torch:add x (torch:forward (torch:field self :sublayer) x)))

(defun skip-connection (d-model d-ff)
  (torch:module :skip-connection (list :sublayer (ffn d-model d-ff))
                (function skip-connection-forward)))

(defparameter *epochs* 40)

(defparameter *data* (torch:tensor (linalg:randn '(64 10))))

(defparameter *plain* (ffn 10 32))

(defparameter *residual* (skip-connection 10 32))

(defparameter *plain-optimizer* (torch:adam *plain* :lr 0.01))

(defparameter *residual-optimizer* (torch:adam *residual* :lr 0.01))

(defun identity-loss (model)
  (torch:mse-loss (torch:forward model *data*) *data*))

(format t "identity training (mean squared error):~%")
(dotimes (epoch *epochs*)
  (let ((plain-loss (identity-loss *plain*))
        (residual-loss (identity-loss *residual*)))
    (when (= 0 (mod epoch 10))
      (format t "  epoch ~2,'0d: FFN ~,4f  SkipConnection ~,4f~%" epoch
              (torch:item plain-loss) (torch:item residual-loss)))
    (torch:zero-grad *plain-optimizer*)
    (torch:zero-grad *residual-optimizer*)
    (torch:backward plain-loss)
    (torch:backward residual-loss)
    (torch:step *plain-optimizer*)
    (torch:step *residual-optimizer*)))

(format t "  final:   FFN ~,4f  SkipConnection ~,4f~%"
        (torch:item (identity-loss *plain*))
        (torch:item (identity-loss *residual*)))
(format t "  the residual block starts and finishes lower: ~a~%"
        (if (< (torch:item (identity-loss *residual*))
               (torch:item (identity-loss *plain*)))
            "yes"
            "no"))

;; --- 2.3.5: layer normalization ---------------------------------------------

(defparameter *activations* (torch:tensor (linalg:randn '(20 5 10))))

(defparameter *normalized* (layer-norm *activations* :eps 1.0e-8))

(defparameter *feature* (torch:slice *normalized* '((3 4) (2 3))))

(format t "normalized shape: ~a~%" (torch:shape *normalized*))
(format t "feature mean:     ~,6f~%" (abs (torch:item (torch:mean *feature*))))
(format t "feature std:      ~,6f~%" (torch:item (torch:std *feature* :ddof 0)))
