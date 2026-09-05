;;;; Pins shortconv.lisp's arithmetic against a transcription of transformers'
;;;; modeling_lfm2.py (Lfm2ShortConv.forward under a cache), in plain float64
;;;; Python, over pseudo-random inputs (shortconv-ref.py, beside this file,
;;;; prints both the literals below and the expected lines; the numbers are
;;;; shown at 3 decimals, none within 2e-5 of a rounding boundary, so
;;;; single-float arithmetic lands on the same text on every backend).
;;;;
;;;;   rontolisp shortconv-check.lisp          # from this directory
;;;;
;;;; Four tokens of a dim-8, kernel-3 layer: the window of two previous inputs
;;;; starts empty, fills, and wraps.

(load "shortconv.lisp")

(defun mat (rows cols flat)
  (let ((m
         (make-array (list rows cols)
                     :element-type 'single-float
                     :initial-element 0.0)))
    (dotimes (r rows m)
      (dotimes (c cols) (setf (aref m r c) (aref flat (+ (* r cols) c)))))))

(defun print-values (v)
  (dotimes (i (length v)) (format t " ~,3f" (aref v i)))
  (terpri))

(defparameter *layer*
  (let ((weights
         (list :rms-att (vector nil)
               :conv-in (vector
                         (mat 24 8
                              #f(0.98 0.06 0.57 0.92 0.20 0.29 -0.98 -0.29 -0.03
                                 -0.58 0.77 -0.98 -0.18 -0.83 -0.96 0.20 -0.23
                                 -0.92 -0.97 0.84 -0.34 -0.96 0.24 -0.47 -0.26
                                 0.22 -0.27 0.91 0.69 0.15 0.01 -0.87 0.34 0.67
                                 0.02 -0.32 -0.75 -0.28 -0.24 0.11 -0.80 -0.40
                                 -0.32 0.03 0.87 -0.29 0.62 -0.55 -0.26 0.23
                                 0.95 -0.69 -0.89 0.76 0.07 -0.69 0.38 -0.50
                                 -0.06 -0.89 0.32 0.15 0.76 -0.88 0.94 -0.88
                                 -0.27 -0.19 0.16 -0.46 0.75 -0.76 0.22 -0.78
                                 -0.80 0.10 0.90 -0.77 -0.50 -0.35 -0.36 0.04
                                 0.93 -0.93 -0.95 0.62 -0.61 -0.18 -0.81 -0.07
                                 -0.16 -0.70 -0.61 -0.09 -0.05 0.31 -0.58 0.02
                                 -0.43 0.05 -0.40 -0.30 -0.24 0.17 0.15 0.70
                                 0.16 0.94 -0.42 -0.34 -0.52 -0.64 -0.82 0.46
                                 0.12 -0.50 0.39 -1.00 -0.50 0.54 0.96 -0.71
                                 -0.45 0.18 -0.06 0.54 -0.10 0.09 0.57 -0.77
                                 0.10 0.06 -0.79 -0.15 1.00 0.09 0.21 -0.47 0.62
                                 0.65 -0.54 -0.14 -0.50 -0.19 0.59 0.63 -0.71
                                 -0.79 0.34 -0.73 -0.89 -0.32 -0.16 -0.68 -0.09
                                 -0.84 0.85 -0.09 -0.39 -0.60 0.76 0.88 0.30
                                 -0.43 0.31 0.02 -0.63 -0.16 0.76 -0.85 0.90
                                 0.72 -0.33 -0.69 0.90 0.05 0.40 0.94 -0.23
                                 -0.85 0.21 -0.62 0.49 0.44 0.87 0.62 0.25 -0.56
                                 0.12 0.71 0.15 0.21)))
               :conv-w (vector
                        (mat 8 3
                             #f(-0.09 0.41 -0.78 0.90 -0.89 0.06 -0.35 0.95
                                -0.67 -0.69 0.88 -0.12 0.97 0.88 -0.78 -0.13
                                -0.58 -0.20 -0.22 0.31 0.66 -0.45 -0.65 -0.65)))
               :conv-out (vector
                          (mat 8 8
                               #f(-0.63 0.65 -0.86 -0.52 0.88 0.01 0.79 0.94
                                  0.56 -0.50 -0.91 0.58 0.62 -0.82 -0.80 0.29
                                  0.63 -0.59 -0.14 -0.78 0.87 -0.46 0.75 0.94
                                  -0.14 0.52 -0.10 0.64 -0.51 -0.55 0.74 -0.34
                                  0.04 -0.15 0.27 -0.69 1.00 0.78 -0.04 0.26
                                  -0.79 0.20 0.13 0.33 -0.37 0.33 -0.23 -0.02
                                  0.66 0.00 -0.79 -0.13 0.66 -0.03 0.85 0.68
                                  0.69 -0.76 0.42 0.04 -0.14 0.02 0.82
                                  -0.15))))))
    (shortconv-layer weights 0 0)))

(defparameter *inputs*
  (list #f(0.15 0.13 -0.13 0.43 -0.99 -0.03 0.53 0.06)
        #f(0.54 -0.67 0.26 -0.07 0.92 0.53 -0.78 0.95)
        #f(0.91 -0.27 0.18 0.09 0.27 0.84 -0.96 -0.75)
        #f(0.71 -0.09 0.65 0.49 0.39 0.73 -0.35 -0.10)))

(let ((st (shortconv-state *layer*)) (n 0))
  (dolist (x *inputs*)
    (format t "t=~a y=" n)
    (print-values (shortconv-forward *layer* st x))
    (setq n (+ n 1))))
