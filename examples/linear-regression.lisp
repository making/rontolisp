;; Least-squares polynomial fitting with the linalg package.
;;
;; Fits a quadratic y = c0 + c1*x + c2*x^2 to five sample points by solving
;; the normal equations (A^T A) c = A^T y, where A is the Vandermonde matrix
;; of the sample xs. Because rontolisp arithmetic is exact for integer and
;; rational inputs, the fitted coefficients and residuals come out as exact
;; ratios -- identical on every backend -- and only the final Euclidean norm
;; is a float.
;;
;; Demonstrates: linalg:from-list, shape, transpose, matmul, dot, solve,
;; det, array-equal, sub, and element access with aref. The residual norm is
;; reported as its exact square (a ratio): (linalg:norm residual) would give
;; the float sqrt, but printing a non-terminating float differs between the
;; JVM and WASM backends (see doc/en/guides/math-backends.md).

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

(defun main ()
  (let* ((xs '(-2 -1 0 1 2))
         (ys '(12 4 2 0 3))
         (degree 2)
         (a (vandermonde xs degree))
         (ata (linalg:matmul (linalg:transpose a) a))
         (coeffs (fit-polynomial xs ys degree)))
    (format t "samples:      xs=~a ys=~a~%" xs ys)
    (format t "vandermonde:  ~a  shape ~a~%" a (linalg:shape a))
    (format t "normal matrix A^T A: ~a  (det ~a)~%" ata (linalg:det ata))
    (format t "coefficients (c0 c1 c2): ~a~%" coeffs)
    ;; The solution is exact: A^T A . c reproduces A^T y bit for bit.
    (format t "exact solution check: ~a~%"
            (linalg:array-equal (linalg:dot ata coeffs)
                                (linalg:dot (linalg:transpose a)
                                            (linalg:from-list ys))))
    (terpri)
    (format t "    x      y      fitted   residual~%")
    (do ((px xs (cdr px))
         (py ys (cdr py)))
        ((null px))
      (let ((fitted (poly-eval coeffs (car px))))
        (format t "  ~a  ~a  ~a  ~a~%"
                (car px) (car py) fitted (- (car py) fitted))))
    (terpri)
    (let ((residual (linalg:sub (linalg:from-list ys)
                                (linalg:dot a coeffs))))
      (format t "squared residual norm (exact): ~a~%"
              (linalg:dot residual residual)))))

(main)
