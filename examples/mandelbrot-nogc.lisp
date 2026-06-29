;;;; Mandelbrot set as ASCII art -- non-GC (--no-gc) edition
;;;; The companion to examples/mandelbrot.lisp. That version prints to stdout;
;;;; this one RETURNS the rendered grid as a string, because --no-gc compiles a
;;;; pure-compute reactor with no WASI imports and no I/O (it runs on any
;;;; MVP-class WebAssembly runtime with NO `-W gc`). The host (JavaScript, a
;;;; small Node script, the browser playground, ...) reads the returned
;;;; (pointer, length) out of the exported linear memory and prints it.
;;;;
;;;; Everything here stays within the --no-gc subset: floating-point arithmetic,
;;;; nested loops (dotimes/while/setq), cond, string literals and
;;;; (concatenate 'string ...). No cons, list, symbol, hash or I/O.
;;;;
;;;; Build (plain MVP module, no wasm-GC):
;;;;   java -jar ...-exec.jar examples/mandelbrot-nogc.lisp -o mandelbrot.wasm --no-gc
;;;;
;;;; Drive it from Node (reads the returned string out of linear memory):
;;;;   const { instance } = await WebAssembly.instantiate(
;;;;     require('fs').readFileSync('mandelbrot.wasm'), {});
;;;;   const ex = instance.exports;
;;;;   const [ptr, len] = ex.mandelbrot(-2.5, 1.0, -1.2, 1.2, 70, 30, 30);
;;;;   process.stdout.write(
;;;;     Buffer.from(new Uint8Array(ex.memory.buffer, ptr, len)).toString());

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

;;; Map an escape time to a single-character shading string.
(defun shade (i max-iter)
  (cond ((>= i max-iter) "#")
        ((>= i 10) "+")
        ((>= i 5) ".")
        (t " ")))

;;; Render the region [x0,x1] x [y0,y1] as a cols x rows grid, accumulating the
;;; characters (and a newline per row) into a single string that is returned.
(defun mandelbrot (x0 x1 y0 y1 cols rows max-iter)
  (let ((dx (/ (- x1 x0) cols))
        (dy (/ (- y1 y0) rows))
        (out ""))
    (dotimes (r rows)
      (let ((cy (+ y0 (* dy r))))
        (dotimes (c cols)
          (setq out (concatenate 'string out
                                 (shade (escape-time (+ x0 (* dx c)) cy max-iter) max-iter))))
        (setq out (concatenate 'string out "
"))))
    out))

;;; Export mandelbrot as a host-callable function: seven scalar inputs, a string out.
(rontolisp:wasm-export 'mandelbrot
  :params '(:float :float :float :float :int :int :int)
  :returns :string)
