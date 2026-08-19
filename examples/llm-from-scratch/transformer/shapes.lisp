;; transformer/shapes.lisp -- the `if __name__ == "__main__"` block of
;; attention.py, plus the same check for the whole model.
;;
;; Everything printed here is a SHAPE or a token count, so the output is one
;; text on every backend regardless of the low-order float digits.
;;
;;   rontolisp transformer/shapes.lisp

(load "transformer.lisp")

(linalg:seed 42)

(defparameter *d-model* 16)

(defparameter *n-heads* 4)

(defparameter *d-k* (/ *d-model* *n-heads*))

(defparameter *d-v* *d-k*)

(defparameter *batch-size* 2)

(defparameter *query-len* 3)

(defparameter *key-len* 4)

(defparameter *query*
  (torch:tensor (linalg:randn (list *batch-size* *query-len* *d-model*))))

(defparameter *key*
  (torch:tensor (linalg:randn (list *batch-size* *key-len* *d-model*))))

(defparameter *value*
  (torch:tensor (linalg:randn (list *batch-size* *key-len* *d-model*))))

(format t "dot-product-attention:        ~a~%"
        (torch:shape (dot-product-attention *query* *key* *value*)))
(format t "scaled-dot-product-attention: ~a~%"
        (torch:shape (scaled-dot-product-attention *query* *key* *value*)))

(defparameter *multi-head*
  (multi-head-attention *n-heads* *d-k* *d-v* *d-model*))

(format t "multi-head-attention:         ~a~%"
        (torch:shape (torch:forward *multi-head* *query* *key* *value*)))
(format t "multi-head parameters:        ~a tensors~%"
        (length (torch:parameters *multi-head*)))

;; The whole model, at the shapes chapter 2 uses for its own smoke test.
(defparameter *model*
  (transformer 11 13 12 *d-model* 2 *n-heads* *d-k* *d-v* (* 2 *d-model*)))

(defparameter *src* (torch:pad-sequence '((2 5 6 7 3) (2 8 9 3))))

(defparameter *tgt* (torch:pad-sequence '((2 4 5 6 3) (2 7 8 3))))

(format t "transformer logits:           ~a~%"
        (torch:shape (torch:forward *model* *src* *tgt*)))
(format t "transformer parameters:       ~a tensors~%"
        (length (torch:parameters *model*)))
(format t "greedy decode length:         ~a tokens~%"
        (length
         (transformer-inference *model* (torch:pad-sequence '((2 5 6 7 3))) 2 3
                                :max-length 6)))
