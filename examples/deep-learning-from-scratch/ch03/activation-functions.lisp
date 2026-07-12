;; ch03 step_function.py / sigmoid.py / relu.py / sig_step_compare.py merged
;; (Deep Learning from Scratch).
;;
;; The four plot scripts draw the activation curves with matplotlib; here
;; the same functions are tabulated at a few sample points instead. The
;; sigmoid column is rounded to 4 decimals; the sample points sit far from
;; rounding boundaries, so the table prints identically on every backend
;; even though WASM's exp is a polynomial approximation (~1e-6 relative).
;;
;;   rontolisp ch03/activation-functions.lisp

(load "../common/functions.lisp")

(defparameter *xs* (linalg:from-list '(-5.0 -2.0 -1.0 -0.5 0.0 0.5 1.0 2.0 5.0)))

(let ((step (step-function *xs*))
      (sig (sigmoid *xs*))
      (rel (relu *xs*)))
  (format t "     x   step  sigmoid    relu~%")
  (dotimes (i (linalg:size *xs*))
    (format t "~6,1f  ~5d  ~7,4f  ~6,1f~%"
            (aref *xs* i)
            (truncate (aref step i))
            (aref sig i)
            (aref rel i))))
