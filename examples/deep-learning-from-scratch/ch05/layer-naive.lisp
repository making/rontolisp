;; ch05/layer_naive.py -- MulLayer and AddLayer (Deep Learning from
;; Scratch). A library file loaded by buy-apple.lisp and
;; buy-apple-orange.lisp.
;;
;; The book's first computational-graph layers, over plain numbers. Their
;; forward takes TWO inputs and backward returns TWO gradients, so they
;; get their own generic pair (forward2 / backward2, the latter returning
;; the list (dx dy)) -- generics have a fixed arity per name in
;; rontolisp's CLOS subset, and the array layers' forward/backward in
;; common/layers.lisp are one-input.

(defgeneric forward2 (layer x y))

(defgeneric backward2 (layer dout))

(defclass mul-layer ()
  ((x :initform nil :accessor mul-layer-x)
   (y :initform nil :accessor mul-layer-y)))

(defmethod forward2 ((layer mul-layer) x y)
  (setf (mul-layer-x layer) x)
  (setf (mul-layer-y layer) y)
  (* x y))

(defmethod backward2 ((layer mul-layer) dout)
  ;; dx = dout * y, dy = dout * x -- the swap is the point of the chapter.
  (list (* dout (mul-layer-y layer)) (* dout (mul-layer-x layer))))

(defclass add-layer ())

(defmethod forward2 ((layer add-layer) x y) (+ x y))

(defmethod backward2 ((layer add-layer) dout) (list dout dout))
