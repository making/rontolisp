;; Least-squares polynomial fitting with the linalg package.
;;
;; Fits a quadratic y = c0 + c1*x + c2*x^2 to five sample points by solving
;; the normal equations (A^T A) c = A^T y, where A is the Vandermonde matrix
;; of the sample xs. linalg computes in packed double-float (see the linear
;; algebra guide), so the fitted coefficients and residuals are floats. A
;; non-terminating float prints at fewer significant digits on WASM than on
;; the interpreter and JVM, so every float result below is reported as a
;; cross-backend-stable integer scaled by 1000 (round(x * 1000)); the integer
;; Vandermonde matrix and the integer-valued normal matrix print directly.
;;
;; Demonstrates: linalg:from-list, shape, transpose, matmul, dot, solve,
;; det, norm, sub, and element access with aref.

(defun vandermonde (xs degree)
  ;; One row per sample: (1 x x^2 ... x^degree).
  (let* ((n (length xs))
         (m (make-array (list n (+ degree 1)))))
    (do ((row 0 (+ row 1))
         (rest xs (cdr rest)))
        ((>= row n) m)
      (do ((col 0 (+ col 1)))
          ((> col degree))
        (setf (aref m row col) (expt (car rest) col))))))

(defun fit-polynomial (xs ys degree)
  ;; Solves (A^T A) c = A^T y for the coefficient vector c.
  (let* ((a (vandermonde xs degree))
         (at (linalg:transpose a))
         (ata (linalg:matmul at a))
         (aty (linalg:dot at (linalg:from-list ys))))
    (linalg:solve ata aty)))

(defun poly-eval (coeffs x)
  ;; Evaluates c0 + c1*x + ... at x (Horner's method).
  (let ((acc 0))
    (do ((i (- (length coeffs) 1) (- i 1)))
        ((< i 0) acc)
      (setq acc (+ (aref coeffs i) (* acc x))))))

(defun scaled (x)
  ;; Rounds a float to a cross-backend-stable integer (x * 1000). A
  ;; non-terminating float prints differently on WASM; the scaled integer
  ;; does not.
  (round (* x 1000)))

(defun main ()
  (let* ((xs '(-2 -1 0 1 2))
         (ys '(12 4 2 0 3))
         (degree 2)
         (a (vandermonde xs degree))
         (at (linalg:transpose a))
         (ata (linalg:matmul at a))
         (aty (linalg:dot at (linalg:from-list ys)))
         (coeffs (fit-polynomial xs ys degree)))
    (format t "samples:      xs=~a ys=~a~%" xs ys)
    (format t "vandermonde:  ~a  shape ~a~%" a (linalg:shape a))
    (format t "normal matrix A^T A: ~a  (det ~a)~%" ata (linalg:det ata))
    (format t "coefficients x1000 (c0 c1 c2): ~a~%"
            (mapcar #'scaled (coerce coeffs 'list)))
    ;; A floating-point least-squares fit: A^T A . c reproduces A^T y to
    ;; within rounding, so the residual of the normal equations is ~0.
    (format t "solution residual < 1e-6: ~a~%"
            (< (linalg:norm (linalg:sub (linalg:dot ata coeffs) aty)) 0.000001))
    (terpri)
    (format t "    x   y   fitted x1000   residual x1000~%")
    (do ((px xs (cdr px))
         (py ys (cdr py)))
        ((null px))
      (let ((fitted (poly-eval coeffs (car px))))
        (format t "  ~a  ~a  ~a  ~a~%"
                (car px) (car py) (scaled fitted) (scaled (- (car py) fitted)))))
    (terpri)
    (let ((residual (linalg:sub (linalg:from-list ys)
                                (linalg:dot a coeffs))))
      (format t "squared residual norm x1000: ~a~%"
              (scaled (linalg:dot residual residual))))))

(main)
