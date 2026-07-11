;; Discrete calculus with the linalg package: numpy-style diff and gradient.
;;
;; linalg:diff is the n-th discrete difference along the last axis (each
;; step shortens the axis by one, and a matrix differences within each
;; row); linalg:gradient estimates the derivative of a vector of samples
;; with second-order central differences (first-order one-sided at the two
;; ends), so it keeps the input length. Every sample below is a polynomial
;; read at integer coordinates, where the difference formulas are exact --
;; the printed doubles are identical on every backend.
;;
;; Demonstrates: linalg:diff (orders 1 and 2, matrix rows), linalg:gradient
;; (unit, scalar and non-uniform coordinate spacing), square, arange, argmax.

(defun main ()
  ;; --- diff: discrete differences ------------------------------------------
  ;; Differencing the Fibonacci numbers reproduces them, shifted by two.
  (let ((fib #(1 1 2 3 5 8 13 21 34)))
    (format t "fib:            ~a~%" fib)
    (format t "diff fib:       ~a~%" (linalg:diff fib)))
  ;; The second difference of the squares is the constant 2 -- the discrete
  ;; analogue of d^2/dx^2 x^2 = 2.
  (let ((squares (linalg:square (linalg:arange 8))))
    (format t "squares:        ~a~%" squares)
    (format t "2nd difference: ~a~%" (linalg:diff squares 2)))
  ;; A matrix differences within each row (the last axis, like numpy).
  (format t "row diffs:      ~a~%" (linalg:diff #2A((1 3 6 10) (0 2 6 12))))
  (terpri)
  ;; --- gradient: projectile motion ------------------------------------------
  ;; Height of a ball thrown straight up at 30 m/s, h(t) = 30t - 5t^2,
  ;; sampled once per second. gradient recovers the velocity: the interior
  ;; points are exact (central differences are exact for a quadratic), the
  ;; two ends are first-order estimates (25 for a true 30).
  (let ((h #(0 25 40 45 40 25 0)))
    (format t "height h(t):    ~a~%" h)
    (format t "velocity:       ~a~%" (linalg:gradient h))
    (format t "apex at t=~a, where the velocity crosses zero~%"
            (linalg:argmax h)))
  ;; The true velocity 30 - 10t is linear, and gradient is exact for a
  ;; linear ramp even at the ends: constant acceleration, -10 m/s^2.
  (format t "acceleration:   ~a~%" (linalg:gradient #(30 20 10 0 -10 -20 -30)))
  (terpri)
  ;; --- gradient: sample spacing ---------------------------------------------
  ;; The same parabola y = x^2 read three ways: at unit spacing, every 2
  ;; units (pass the scalar spacing), and unevenly at x = (0 1 3 7) (pass
  ;; the coordinate vector -- the non-uniform interior formula is still
  ;; exact for a quadratic: 2x at x = 1 and 3).
  (format t "unit spacing:   ~a~%" (linalg:gradient #(0 1 4 9 16)))
  (format t "spacing 2:      ~a~%" (linalg:gradient #(0 4 16 36 64) 2))
  (format t "at x=(0 1 3 7): ~a~%" (linalg:gradient #(0 1 9 49) #(0 1 3 7))))

(main)
