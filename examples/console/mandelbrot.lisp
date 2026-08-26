;;;; Mandelbrot set as ASCII art in rontolisp
;;;; Renders the Mandelbrot set to the terminal using only floating-point
;;;; arithmetic and nested loops -- no transcendental functions -- so it runs
;;;; identically on all three backends (interpreter / JVM / WASM). The iteration
;;;; cap is threaded through as an argument, which is also idiomatic CL; the
;;;; JVM/WASM compilers can equally read a global special variable from inside a
;;;; function body.
;;;;
;;;; Run:
;;;;   java -jar target/rontolisp-0.1.0-SNAPSHOT-exec.jar examples/console/mandelbrot.lisp
;;;;   java -jar ...-exec.jar examples/console/mandelbrot.lisp -o Prog.class && java Prog
;;;;   java -jar ...-exec.jar examples/console/mandelbrot.lisp -o mandelbrot.wasm && wasmtime run mandelbrot.wasm

;;; Escape time for the complex point (cx, cy): the number of iterations of
;;; z <- z^2 + c before |z| > 2 (i.e. |z|^2 > 4), capped at `max-iter`.
(defun escape-time (cx cy max-iter)
  (let ((x 0.0) (y 0.0) (i 0))
    (while (and (< i max-iter) (<= (+ (* x x) (* y y)) 4.0))
      (let ((xt (+ (- (* x x) (* y y)) cx)))
        (setq y (+ (* 2.0 (* x y)) cy))
        (setq x xt))
      (setq i (+ i 1)))
    i))

;;; Map an escape time to a shading character: dense for points that stay
;;; bounded ("inside"), sparse for points that escape quickly.
(defun shade (i max-iter)
  (cond ((>= i max-iter) "#") ((>= i 10) "+") ((>= i 5) ".") (t " ")))

;;; Render the region [x0,x1] x [y0,y1] as a cols x rows grid of characters.
(defun mandelbrot (x0 x1 y0 y1 cols rows max-iter)
  (let ((dx (/ (- x1 x0) cols)) (dy (/ (- y1 y0) rows)))
    (dotimes (r rows)
      (let ((cy (+ y0 (* dy r))))
        (dotimes (c cols)
          (princ (shade (escape-time (+ x0 (* dx c)) cy max-iter) max-iter)))
        (terpri)))))

(defparameter *max-iter* 30)
(format t "Mandelbrot set (~d iterations):~%" *max-iter*)
(mandelbrot -2.5 1.0 -1.2 1.2 70 30 *max-iter*)
