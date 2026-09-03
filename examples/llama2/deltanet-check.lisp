;;;; Pins deltanet.lisp's arithmetic against a transcription of transformers'
;;;; modeling_qwen3_5.py, in plain float64 Python, over pseudo-random inputs
;;;; (deltanet-ref.py, beside this file, prints both the literals below and the
;;;; expected lines; the numbers are shown at 3 decimals, none within 2e-5 of a
;;;; rounding boundary, so single-float arithmetic and the WASM exp
;;;; approximation land on the same text on every backend).
;;;;
;;;;   rontolisp deltanet-check.lisp          # from this directory
;;;;
;;;; Part 1 is torch_recurrent_gated_delta_rule alone: heads 2, dim 4, three
;;;; tokens, the state carried across them, then the final states. Part 2 is
;;;; the whole decode step -- causal_conv1d_update, the SiLU, the l2norm, the
;;;; beta / decay gates, the recurrence, Qwen3_5RMSNormGated, out_proj -- over
;;;; five tokens of a dim-8, two-head, kernel-4 layer, so the convolution
;;;; window wraps fully.

(load "deltanet.lisp")

(defun fresh (v)
  ;; a mutable copy of a literal vector (the step normalises q and k in place)
  (vec:scale v 1.0))

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

;;; --- part 1: the recurrence ------------------------------------------------------

(defparameter *tokens*
  ;; per token, per head: q k v g beta
  (list (list #f(-0.95 0.50 0.83 0.81) #f(0.67 -0.07 -0.13 -0.03)
              #f(0.11 -0.07 -0.56 0.04) -1.23 0.74)
        (list #f(-0.97 -0.48 -0.77 0.66) #f(-0.52 -0.40 0.75 0.42)
              #f(0.32 0.13 -0.85 0.23) -1.21 0.82)
        (list #f(0.03 -0.13 -0.04 0.65) #f(0.31 0.88 -0.79 0.81)
              #f(-0.11 -0.45 0.83 -0.82) -1.07 0.08)
        (list #f(0.34 -0.57 -0.71 0.08) #f(0.99 -0.19 -0.52 -0.81)
              #f(0.90 -0.91 -0.87 0.98) -1.91 0.70)
        (list #f(0.08 0.16 -0.27 0.79) #f(-0.04 -0.97 0.12 0.72)
              #f(0.71 0.41 -0.40 0.60) -0.33 0.96)
        (list #f(-0.51 -0.66 -0.80 0.87) #f(-0.37 -0.58 -0.26 0.39)
              #f(-0.05 -0.83 0.23 -0.01) -1.08 0.96)))

(let ((states
       (vector (linalg:zeros '(4 4) :element-type 'single-float)
               (linalg:zeros '(4 4) :element-type 'single-float)))
      (o (vec:zeros 4 :element-type 'single-float))
      (n 0))
  (dolist (tok *tokens*)
    (let ((step (floor n 2)) (h (mod n 2)))
      (gated-delta-rule (aref states h) (fresh (first tok)) (fresh (second tok))
                        (fresh (third tok)) (fourth tok) (fifth tok) o)
      (format t "t=~a h=~a o=" step h)
      (print-values o))
    (setq n (+ n 1)))
  (dotimes (h 2)
    (dotimes (j 4)
      (format t "h=~a S^T[~a]=" h j)
      (print-values (linalg:row (aref states h) j)))))

;;; --- part 2: the decode step -----------------------------------------------------

(defparameter *layer*
  (let ((weights
         (list :rms-att (vector nil)
               :ssm-qkv (vector
                         (mat 24 8
                              #f(0.67 -0.42 0.26 0.43 -0.41 0.15 -0.04 -0.12
                                 0.89 -0.28 -0.72 0.63 0.54 0.12 0.86 0.17 -0.80
                                 -0.57 0.30 0.59 -0.24 0.31 -0.86 -0.44 -0.05
                                 0.12 -0.54 -0.48 0.91 0.08 0.50 -0.88 0.22
                                 -0.89 -0.12 -0.28 -0.94 -0.46 -0.40 -0.78 0.02
                                 -0.09 0.60 -0.56 0.15 0.24 0.19 -0.05 -0.31
                                 -0.79 -0.36 0.76 -0.45 -0.06 0.54 -0.99 -0.16
                                 -0.46 -0.01 0.13 0.49 -0.21 0.64 -0.36 -0.06
                                 0.71 -0.52 -0.74 0.26 0.02 0.44 0.73 -0.78 0.68
                                 0.08 -0.77 -0.52 -0.54 0.31 -0.42 0.51 -0.93
                                 0.15 0.16 0.95 0.58 -0.08 0.79 0.45 0.99 0.33
                                 0.31 0.79 -0.64 0.62 0.13 -0.32 0.71 0.61 0.21
                                 0.19 0.46 0.62 0.54 -0.29 0.10 -0.31 -0.59
                                 -0.10 -0.63 -0.00 -0.83 -0.15 0.10 -0.06 -0.29
                                 0.87 -0.15 0.81 -0.44 0.51 -0.73 -0.53 0.88
                                 0.71 -0.78 0.58 0.47 0.02 -0.54 -0.76 -0.41
                                 -0.62 -0.60 0.11 0.08 0.99 0.62 -0.59 -0.80
                                 -0.63 -0.04 -0.89 -0.66 -0.91 0.68 0.90 0.63
                                 -0.89 -0.90 0.80 0.60 0.15 0.23 -0.54 -0.30
                                 -0.08 -0.54 0.48 -0.36 0.60 -0.05 -0.12 -0.37
                                 0.79 0.07 -0.27 0.91 0.99 0.57 0.31 0.65 -0.32
                                 0.66 0.47 0.16 0.56 0.86 0.34 -0.72 -0.19 0.35
                                 0.48 0.81 0.88 -0.81 0.32 0.83 0.71 0.22 -0.10
                                 -0.17)))
               :ssm-z (vector
                       (mat 8 8
                            #f(-0.79 -0.33 0.64 0.79 -0.22 -0.93 1.00 -0.21 0.27
                               -0.08 0.00 -0.73 0.49 -0.65 0.85 0.86 -0.89 -0.09
                               0.53 0.98 0.57 0.15 -0.95 -0.29 0.35 0.96 0.22
                               -0.11 0.10 -0.93 -0.26 0.18 0.30 0.42 0.48 -0.73
                               0.65 0.20 0.52 -0.02 -0.09 0.26 0.46 0.52 -0.60
                               0.88 0.08 -0.33 0.24 0.46 -0.59 0.09 0.32 0.81
                               -0.18 -0.59 -0.77 -0.45 0.88 -0.70 0.68 -0.00
                               0.42 0.26)))
               :ssm-beta (vector
                          (mat 2 8
                               #f(-0.29 0.03 0.90 -0.92 0.76 -0.50 0.78 -0.64
                                  -0.59 0.64 -0.06 -0.48 0.83 -0.70 -0.15
                                  0.82)))
               :ssm-alpha (vector
                           (mat 2 8
                                #f(-0.47 -0.94 0.16 -0.46 -0.27 -0.23 -0.32
                                   -0.77 -0.09 0.39 0.71 0.21 -0.31 0.63 0.54
                                   -0.43)))
               :ssm-conv (vector
                          (mat 24 4
                               #f(-0.06 -0.84 0.74 -1.00 -0.30 -0.42 -0.90 -0.59
                                  -0.29 0.77 0.90 0.09 0.44 -0.79 0.00 0.23
                                  -0.82 -0.46 -0.47 -0.91 0.31 0.64 -0.85 0.21
                                  0.78 -0.94 0.51 0.75 -0.29 0.09 0.81 -0.42
                                  -0.66 0.25 0.19 0.57 -0.80 0.57 -0.61 0.41
                                  -0.26 -0.26 0.03 0.48 0.10 0.60 -0.88 -0.44
                                  -0.03 0.32 -0.55 -0.38 -0.04 -0.01 0.87 0.07
                                  -0.83 -0.17 0.30 -0.31 -0.15 0.83 -0.54 -0.30
                                  0.10 -0.44 0.91 -0.09 0.36 -0.62 -0.60 -0.14
                                  -0.34 0.38 -0.69 0.66 -0.95 -0.40 0.94 -0.44
                                  0.50 -0.04 -0.39 -0.32 -0.62 -0.55 -0.34 -0.94
                                  0.25 -0.90 -0.03 0.24 0.93 -0.01 -0.80 0.96)))
               :ssm-a (vector #f(-1.08 -0.87))
               :ssm-dt-bias (vector #f(-0.87 0.59))
               :ssm-norm (vector #f(0.67 1.47 0.91 0.53))
               :ssm-out (vector
                         (mat 8 8
                              #f(0.30 0.55 -0.41 -0.37 -0.78 0.73 -0.35 -0.08
                                 0.36 0.42 -0.58 0.67 -0.09 -0.43 0.78 -0.56
                                 -0.96 0.50 -0.86 0.25 -0.12 0.93 -0.79 -0.94
                                 -0.08 -0.08 0.04 -0.09 0.33 -0.45 0.08 -0.98
                                 -0.54 -0.49 -0.58 -0.70 0.14 0.79 0.25 0.88
                                 0.29 -0.66 -0.36 0.82 0.73 -0.03 0.85 -0.93
                                 -0.38 0.73 0.14 -0.33 -0.16 0.85 0.28 0.70 0.52
                                 -0.59 0.05 -0.17 -0.64 -0.47 -0.37 -0.64))))))
    (deltanet-layer weights 0 0)))

(defparameter *inputs*
  (list #f(0.21 0.68 -0.65 -0.40 -0.28 0.53 0.44 0.49)
        #f(-0.51 -0.02 -0.81 0.70 -0.47 -0.27 0.05 0.76)
        #f(-0.25 -0.57 0.98 -0.16 0.64 -0.49 -0.81 0.07)
        #f(-0.80 -0.93 -0.51 -0.67 -0.99 0.68 0.91 0.08)
        #f(0.37 -0.17 -0.99 -0.07 0.88 0.79 -0.17 0.08)))

(let ((st (deltanet-state *layer*)) (n 0))
  (dolist (x *inputs*)
    (format t "t=~a y=" n)
    (print-values (deltanet-forward *layer* st x 0.000001))
    (setq n (+ n 1))))
