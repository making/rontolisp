;; ch06/batch_norm_gradient_check.py -- backprop through Batch
;; Normalization vs numerical gradients (Deep Learning from Scratch).
;;
;; The BatchNormalization backward pass is the most intricate derivation
;; in the book; this check pins it against central differences. Like
;; ch05/gradient-check.lisp, a small seeded 8-(5 4)-3 net over a random
;; batch replaces the book's 784-100-100-10 MNIST batch (numerically
;; differentiating that takes hours of interpreter time) -- same
;; mathematics, no data files. gamma/beta gradients are checked too.
;;
;;   rontolisp ch06/batch-norm-gradient-check.lisp

(load "../common/multi-layer-net-extend.lisp")

(linalg:seed 42)

(let* ((net (make-multi-layer-net-extend 8 '(5 4) 3 :use-batchnorm t))
       (x-batch (linalg:randn '(4 8)))
       (t-batch (linalg:one-hot (linalg:from-list '(1 0 2 1)) 3))
       (grad-numerical (net-numerical-gradient net x-batch t-batch))
       (grad-backprop (net-gradient net x-batch t-batch)))
  (dolist (key (mlne-keys net))
    (let* ((gn (gethash key grad-numerical))
           (gb (gethash key grad-backprop))
           (diff (/ (linalg:sum (linalg:abs (linalg:sub gb gn)))
                    (linalg:size gn))))
      (format t "~a: ~a~%" key
              (if (< diff 1.0e-5) "PASS (mean |diff| < 1e-5)" "FAIL")))))
