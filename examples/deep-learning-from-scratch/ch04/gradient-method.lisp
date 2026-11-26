;; ch04/gradient_method.py -- gradient descent on f(x0, x1) = x0^2 + x1^2
;; (Deep Learning from Scratch).
;;
;; 20 steps from (-3, 4) with learning rate 0.1, printing the trajectory
;; the book plots. The book's text also shows why the learning rate
;; matters; the too-large / too-small runs are reproduced after the
;; well-tuned one.
;;
;;   rontolisp ch04/gradient-method.lisp

(load "../common/gradient.lisp")

(defun function-2 (x) (linalg:sum (linalg:square x)))

(defun gradient-descent (f init-x lr step-num &optional print-steps)
  ;; Returns x after step-num updates x <- x - lr * grad; with print-steps,
  ;; prints every 5th position along the way.
  (let ((x init-x))
    (dotimes (i step-num)
      (when (and print-steps (= (mod i 5) 0))
        (format t "  step ~2d: (~,6f, ~,6f)~%" i (aref x 0) (aref x 1)))
      (let ((grad (numerical-gradient f x)))
        (setq x (linalg:sub x (linalg:mul lr grad)))))
    x))

(format t "lr = 0.1 (well-tuned):~%")
(let ((x
       (gradient-descent (function function-2) (linalg:from-list '(-3.0 4.0))
                         0.1 20 t)))
  (format t "  final:   (~,6f, ~,6f)~%" (aref x 0) (aref x 1)))

;; Too large a learning rate diverges; too small barely moves (the book's
;; lr=10.0 / lr=1e-10 comparison, 100 steps each).
(let ((x
       (gradient-descent (function function-2) (linalg:from-list '(-3.0 4.0))
                         10.0 100)))
  (format t "lr = 10.0:  |x| ~a 1e10 after 100 steps~%"
          (if (> (linalg:norm x) 1.0e10) "diverged beyond" "stayed under")))
(let ((x
       (gradient-descent (function function-2) (linalg:from-list '(-3.0 4.0))
                         1.0e-10 100)))
  (format t "lr = 1e-10: still at (~,4f, ~,4f) after 100 steps~%" (aref x 0)
          (aref x 1)))
