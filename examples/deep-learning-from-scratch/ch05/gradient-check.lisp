;; ch05/gradient_check.py -- backpropagation vs numerical gradients (Deep
;; Learning from Scratch).
;;
;; The correctness gate of the whole layer library: the analytic gradients
;; from backprop must agree with the ch04 central differences to ~1e-9.
;; The book checks a 3-image MNIST batch through the full 784-50-10 net;
;; numerically differentiating its 39760 parameters is hours of
;; interpreter time, so this port checks a 20-10-10 net on a seeded random
;; batch instead -- the same mathematics, no data files, runs anywhere in
;; under a second. The raw mean differences are ~1e-10 and their last
;; digits differ per backend, so each key prints a PASS/FAIL verdict
;; against 1e-6 instead of the raw float.
;;
;;   rontolisp ch05/gradient-check.lisp

(load "two-layer-net.lisp")

(linalg:seed 42)

(let* ((net (make-two-layer-net 20 10 10))
       (x-batch (linalg:randn '(3 20)))
       (t-batch (linalg:one-hot (linalg:from-list '(1 7 3)) 10))
       (grad-numerical (net-numerical-gradient net x-batch t-batch))
       (grad-backprop (net-gradient net x-batch t-batch)))
  (dolist (key *tln-keys*)
    (let* ((gn (gethash key grad-numerical))
           (gb (gethash key grad-backprop))
           (diff (/ (linalg:sum (linalg:abs (linalg:sub gb gn)))
                    (linalg:size gn))))
      (format t "~a: ~a~%" key
              (if (< diff 1.0e-6) "PASS (mean |diff| < 1e-6)" "FAIL")))))
