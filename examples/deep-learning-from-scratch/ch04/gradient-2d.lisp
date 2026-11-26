;; ch04/gradient_2d.py -- the gradient of f(x0, x1) = x0^2 + x1^2
;; (Deep Learning from Scratch).
;;
;; The book draws the gradient field with a quiver plot; here the gradient
;; is printed at the three sample points the book's text discusses. The
;; true gradient is (2*x0, 2*x1).
;;
;;   rontolisp ch04/gradient-2d.lisp

(load "../common/gradient.lisp")

(defun function-2 (x)
  ;; x[0]^2 + x[1]^2 over the whole vector.
  (linalg:sum (linalg:square x)))

(dolist (pt '((3.0 4.0) (0.0 2.0) (3.0 0.0)))
  (let ((g (numerical-gradient (function function-2) (linalg:from-list pt))))
    (format t "grad at (~a, ~a) = (~,4f, ~,4f)~%" (car pt) (cadr pt) (aref g 0)
            (aref g 1))))
