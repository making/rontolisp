;; common/layers.py -- the class-based layers (Deep Learning from Scratch),
;; on rontolisp's CLOS subset: one class per layer, a forward/backward
;; generic-function pair, and per-instance state (cached inputs, masks,
;; dW/db) in slots. Two adaptations to the subset's fixed-arity generics:
;;
;; - the book's train_flg parameter becomes the special variable *train-p*
;;   (bind (let ((*train-p* nil)) ...) around evaluation);
;; - SoftmaxWithLoss's two-argument forward gets its own generic pair,
;;   loss-forward / loss-backward.
;;
;; Accessor names are class-prefixed because CLOS accessors are plain
;; defuns here (globally unique names required).

(load "functions.lisp")
(load "util.lisp")

(defvar *train-p* t)

(defgeneric forward (layer x))

(defgeneric backward (layer dout))

;; --- Relu ----------------------------------------------------------------------

(defclass relu-layer () ((mask :initform nil :accessor relu-mask)))

(defmethod forward ((layer relu-layer) x)
  ;; The book keeps mask = (x <= 0) and zeroes through it; the equivalent
  ;; kept-elements mask (x > 0) is stored instead and multiplied in.
  (let ((keep (linalg:greater x 0)))
    (setf (relu-mask layer) keep)
    (linalg:mul x keep)))

(defmethod backward ((layer relu-layer) dout)
  (linalg:mul dout (relu-mask layer)))

;; --- Sigmoid -------------------------------------------------------------------

(defclass sigmoid-layer () ((out :initform nil :accessor sigmoid-out)))

(defmethod forward ((layer sigmoid-layer) x)
  (let ((out (sigmoid x)))
    (setf (sigmoid-out layer) out)
    out))

(defmethod backward ((layer sigmoid-layer) dout)
  (let ((out (sigmoid-out layer)))
    (linalg:mul dout (linalg:mul (linalg:sub 1 out) out))))

;; --- Affine --------------------------------------------------------------------

(defclass affine ()
  ((w :initarg :w :accessor affine-w) ; SHARED with params["W<i>"]
   (b :initarg :b :accessor affine-b) (x :initform nil :accessor affine-x)
   (original-shape :initform nil :accessor affine-original-shape)
   (dw :initform nil :accessor affine-dw)
   (db :initform nil :accessor affine-db)))

(defmethod forward ((layer affine) x)
  ;; Tensor support like the book: a rank > 2 input is flattened to
  ;; (N, -1) for the product and restored in backward.
  (let* ((shape (linalg:shape x))
         (x2 (if (cdr (cdr shape)) (linalg:reshape x (list (car shape) -1)) x)))
    (setf (affine-original-shape layer) shape)
    (setf (affine-x layer) x2)
    (linalg:add (linalg:matmul x2 (affine-w layer)) (affine-b layer))))

(defmethod backward ((layer affine) dout)
  (let ((dx (linalg:matmul dout (linalg:transpose (affine-w layer)))))
    (setf (affine-dw layer)
          (linalg:matmul (linalg:transpose (affine-x layer)) dout))
    (setf (affine-db layer) (linalg:sum dout 0))
    (linalg:reshape dx (affine-original-shape layer))))

;; --- SoftmaxWithLoss -----------------------------------------------------------

(defclass softmax-with-loss ()
  ((loss :initform nil :accessor swl-loss) (y :initform nil :accessor swl-y)
   (target :initform nil :accessor swl-target)))

(defgeneric loss-forward (layer x target))

(defgeneric loss-backward (layer))

(defmethod loss-forward ((layer softmax-with-loss) x target)
  (setf (swl-target layer) target)
  (setf (swl-y layer) (softmax x))
  (setf (swl-loss layer) (cross-entropy-error (swl-y layer) target))
  (swl-loss layer))

(defmethod loss-backward ((layer softmax-with-loss))
  ;; dx = (y - one-hot(t)) / batch. The book scatters -1 into a copy of y
  ;; when t is a label vector (dx[np.arange(batch), t] -= 1); subtracting
  ;; the one-hot matrix is the same arithmetic without mutation.
  (let* ((y (swl-y layer))
         (target (swl-target layer))
         (batch (car (linalg:shape y)))
         (hot
          (if (= (linalg:size target) (linalg:size y))
              target
              (linalg:one-hot target (car (cdr (linalg:shape y)))))))
    (linalg:div (linalg:sub y hot) batch)))

;; --- Dropout -------------------------------------------------------------------

(defclass dropout ()
  ((ratio :initarg :ratio :initform 0.5 :accessor dropout-ratio)
   (mask :initform nil :accessor dropout-mask)))

(defmethod forward ((layer dropout) x)
  (if *train-p*
      (let ((mask
             (linalg:greater (linalg:rand (linalg:shape x))
                             (dropout-ratio layer))))
        (setf (dropout-mask layer) mask)
        (linalg:mul x mask))
      (linalg:mul x (- 1.0 (dropout-ratio layer)))))

(defmethod backward ((layer dropout) dout)
  (linalg:mul dout (dropout-mask layer)))

;; --- Convolution (ch07) ----------------------------------------------------------

(defclass convolution ()
  ((w :initarg :w :accessor conv-w) ; (FN C FH FW), SHARED with params
   (b :initarg :b :accessor conv-b) ; (FN)
   (stride :initarg :stride :initform 1 :accessor conv-stride)
   (pad :initarg :pad :initform 0 :accessor conv-pad)
   (x :initform nil :accessor conv-x) (col :initform nil :accessor conv-col)
   (col-w :initform nil :accessor conv-col-w)
   (dw :initform nil :accessor conv-dw) (db :initform nil :accessor conv-db)))

(defmethod forward ((layer convolution) x)
  ;; im2col turns the convolution into one (N*oh*ow, C*FH*FW) x
  ;; (C*FH*FW, FN) matrix product; the result folds back to NCHW through
  ;; reshape + transpose like the book.
  (let* ((wd (linalg:shape (conv-w layer)))
         (fn (car wd))
         (fh (nth 2 wd))
         (fw (nth 3 wd))
         (xd (linalg:shape x))
         (n (car xd))
         (h (nth 2 xd))
         (w (nth 3 xd))
         (stride (conv-stride layer))
         (pad (conv-pad layer))
         (out-h (+ 1 (floor (- (+ h (* 2 pad)) fh) stride)))
         (out-w (+ 1 (floor (- (+ w (* 2 pad)) fw) stride)))
         (col (im2col x fh fw stride pad))
         (col-w (linalg:transpose (linalg:reshape (conv-w layer) (list fn -1))))
         (out (linalg:add (linalg:matmul col col-w) (conv-b layer))))
    (setf (conv-x layer) x)
    (setf (conv-col layer) col)
    (setf (conv-col-w layer) col-w)
    (linalg:transpose (linalg:reshape out (list n out-h out-w -1)) '(0 3 1 2))))

(defmethod backward ((layer convolution) dout)
  ;; dout (N FN oh ow) -> (N*oh*ow, FN); dW/db fall out of the matrix
  ;; product's transposes, and dx scatters back through col2im.
  (let* ((wd (linalg:shape (conv-w layer)))
         (fn (car wd))
         (c (nth 1 wd))
         (fh (nth 2 wd))
         (fw (nth 3 wd))
         (dout2
          (linalg:reshape (linalg:transpose dout '(0 2 3 1)) (list -1 fn))))
    (setf (conv-db layer) (linalg:sum dout2 0))
    (setf (conv-dw layer)
          (linalg:reshape (linalg:transpose
                           (linalg:matmul (linalg:transpose (conv-col layer))
                                          dout2)) (list fn c fh fw)))
    (col2im (linalg:matmul dout2 (linalg:transpose (conv-col-w layer)))
            (linalg:shape (conv-x layer)) fh fw (conv-stride layer)
            (conv-pad layer))))

;; --- Pooling (ch07) --------------------------------------------------------------

(defclass pooling ()
  ((pool-h :initarg :pool-h :accessor pool-h)
   (pool-w :initarg :pool-w :accessor pool-w)
   (stride :initarg :stride :initform 1 :accessor pool-stride)
   (pad :initarg :pad :initform 0 :accessor pool-pad)
   (x :initform nil :accessor pool-x)
   (arg-max :initform nil :accessor pool-arg-max)))

(defmethod forward ((layer pooling) x)
  ;; Max pooling over the im2col unfold: reshaping the (N*oh*ow, C*ph*pw)
  ;; matrix to (-1, ph*pw) makes each row one window of one channel, so
  ;; the max and its argmax are per-row axis-1 reductions (the book).
  (let* ((xd (linalg:shape x))
         (n (car xd))
         (c (nth 1 xd))
         (h (nth 2 xd))
         (w (nth 3 xd))
         (ph (pool-h layer))
         (pw (pool-w layer))
         (stride (pool-stride layer))
         (out-h (+ 1 (floor (- h ph) stride)))
         (out-w (+ 1 (floor (- w pw) stride)))
         (col
          (linalg:reshape (im2col x ph pw stride (pool-pad layer))
                          (list -1 (* ph pw))))
         (am (linalg:argmax col 1))
         (out (linalg:amax col 1)))
    (setf (pool-x layer) x)
    (setf (pool-arg-max layer) am)
    (linalg:transpose (linalg:reshape out (list n out-h out-w c)) '(0 3 1 2))))

(defmethod backward ((layer pooling) dout)
  ;; The book scatters dout into a zero (size, pool-size) matrix at each
  ;; window's cached argmax (dmax[np.arange(size), argmax] = dout);
  ;; multiplying the argmax one-hot matrix by the dout column is the same
  ;; scatter without mutation (the loss-backward pattern).
  (let* ((dd (linalg:shape dout))
         (n (car dd))
         (oh (nth 2 dd))
         (ow (nth 3 dd))
         (pool-size (* (pool-h layer) (pool-w layer)))
         (flat (linalg:reshape (linalg:transpose dout '(0 2 3 1)) (list -1 1)))
         (dmax
          (linalg:mul (linalg:one-hot (pool-arg-max layer) pool-size) flat))
         (dcol (linalg:reshape dmax (list (* n oh ow) -1))))
    (col2im dcol (linalg:shape (pool-x layer)) (pool-h layer) (pool-w layer)
            (pool-stride layer) (pool-pad layer))))

;; --- BatchNormalization ---------------------------------------------------------

(defclass batch-normalization ()
  ((gamma :initarg :gamma :accessor bn-gamma) ; SHARED with params["gamma<i>"]
   (beta :initarg :beta :accessor bn-beta)
   (momentum :initarg :momentum :initform 0.9 :accessor bn-momentum)
   (input-shape :initform nil :accessor bn-input-shape)
   (running-mean :initform nil :accessor bn-running-mean)
   (running-var :initform nil :accessor bn-running-var)
   (batch-size :initform nil :accessor bn-batch-size)
   (xc :initform nil :accessor bn-xc) (xn :initform nil :accessor bn-xn)
   (std :initform nil :accessor bn-std)
   (dgamma :initform nil :accessor bn-dgamma)
   (dbeta :initform nil :accessor bn-dbeta)))

(defmethod forward ((layer batch-normalization) x)
  (setf (bn-input-shape layer) (linalg:shape x))
  (let* ((x2
          (if (/= (linalg:ndim x) 2)
              (linalg:reshape x (list (car (linalg:shape x)) -1))
              x))
         (d (car (cdr (linalg:shape x2)))))
    (unless (bn-running-mean layer)
      (setf (bn-running-mean layer) (linalg:zeros d))
      (setf (bn-running-var layer) (linalg:zeros d)))
    (let ((out
           (if *train-p*
               (let* ((mu (linalg:mean x2 0))
                      (xc (linalg:sub x2 mu))
                      (var (linalg:mean (linalg:square xc) 0))
                      (std (linalg:sqrt (linalg:add var 10.0e-7)))
                      (xn (linalg:div xc std))
                      (m (bn-momentum layer)))
                 (setf (bn-batch-size layer) (car (linalg:shape x2)))
                 (setf (bn-xc layer) xc)
                 (setf (bn-xn layer) xn)
                 (setf (bn-std layer) std)
                 (setf (bn-running-mean layer)
                       (linalg:add (linalg:mul m (bn-running-mean layer))
                                   (linalg:mul (- 1 m) mu)))
                 (setf (bn-running-var layer)
                       (linalg:add (linalg:mul m (bn-running-var layer))
                                   (linalg:mul (- 1 m) var)))
                 xn)
               (linalg:div (linalg:sub x2 (bn-running-mean layer))
                (linalg:sqrt (linalg:add (bn-running-var layer) 10.0e-7))))))
      (linalg:reshape
       (linalg:add (linalg:mul (bn-gamma layer) out) (bn-beta layer))
       (bn-input-shape layer)))))

(defmethod backward ((layer batch-normalization) dout)
  (let* ((dout2
          (if (/= (linalg:ndim dout) 2)
              (linalg:reshape dout (list (car (linalg:shape dout)) -1))
              dout))
         (xn (bn-xn layer))
         (xc (bn-xc layer))
         (std (bn-std layer))
         (batch (bn-batch-size layer))
         (dbeta (linalg:sum dout2 0))
         (dgamma (linalg:sum (linalg:mul xn dout2) 0))
         (dxn (linalg:mul (bn-gamma layer) dout2))
         (dxc (linalg:div dxn std))
         (dstd
          (linalg:negative
           (linalg:sum (linalg:div (linalg:mul dxn xc) (linalg:mul std std))
                       0)))
         (dvar (linalg:div (linalg:mul 0.5 dstd) std))
         (dxc2 (linalg:add dxc (linalg:mul (/ 2.0 batch) (linalg:mul xc dvar))))
         (dmu (linalg:sum dxc2 0))
         (dx (linalg:sub dxc2 (linalg:div dmu batch))))
    (setf (bn-dgamma layer) dgamma)
    (setf (bn-dbeta layer) dbeta)
    (linalg:reshape dx (bn-input-shape layer))))

;; --- parameter re-pointing (ch07/ch08 net-load-params) --------------------------

;; The book's load_params reassigns layer.W/layer.b after replacing the
;; params dict; conv and affine name those slots differently, so one
;; generic folds both (the layer-dw/layer-db pattern of ch08).

(defgeneric layer-set-params (layer w b))

(defmethod layer-set-params ((layer convolution) w b)
  (setf (conv-w layer) w)
  (setf (conv-b layer) b))

(defmethod layer-set-params ((layer affine) w b)
  (setf (affine-w layer) w)
  (setf (affine-b layer) b))
