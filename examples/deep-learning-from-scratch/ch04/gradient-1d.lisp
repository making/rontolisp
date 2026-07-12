;; ch04/gradient_1d.py -- numerical differentiation (Deep Learning from
;; Scratch).
;;
;; numerical_diff of f(x) = 0.01x^2 + 0.1x at x = 5 and x = 10 (the book
;; also draws the tangent lines; the printed derivatives are the point).
;; The true derivative is 0.02x + 0.1, i.e. 0.2 and 0.3.
;;
;;   rontolisp ch04/gradient-1d.lisp

(defun numerical-diff (f x)
  ;; (f(x+h) - f(x-h)) / 2h with h = 1e-4.
  (let ((h 1.0e-4))
    (/ (- (funcall f (+ x h)) (funcall f (- x h)))
       (* 2 h))))

(defun function-1 (x)
  (+ (* 0.01 x x) (* 0.1 x)))

;; The central difference of a quadratic is exact up to float rounding;
;; round to 8 decimals so the output is identical on every backend (the
;; WASM ~f scaling must stay inside the i31 integer range).
(format t "df/dx at  5: ~,8f~%" (numerical-diff (function function-1) 5.0))
(format t "df/dx at 10: ~,8f~%" (numerical-diff (function function-1) 10.0))
