;;;; Mandelbrot set as ASCII art -- non-GC (--no-gc) edition
;;;; The companion to examples/console/mandelbrot.lisp. That version prints to stdout;
;;;; this one RETURNS the rendered grid as a string, because --no-gc compiles a
;;;; pure-compute reactor with no WASI imports and no I/O (it runs on any
;;;; MVP-class WebAssembly runtime with NO `-W gc`).
;;;;
;;;; Everything here stays within the --no-gc subset: floating-point arithmetic,
;;;; nested loops (dotimes/while/setq), cond, string literals and
;;;; (concatenate 'string ...). No cons, list, symbol, hash or I/O.
;;;;
;;;; The export is not described here: mandelbrot_component.wit is, and
;;;; rontolisp:wit-export at the bottom says "this program implements that world".
;;;; The compiler reads the .wit, checks the export it declares against the defun
;;;; below -- name, arity, parameter and result types -- and lowers it into the
;;;; export directive it stands for, so one directive serves both builds and a
;;;; drifted contract is a compile error naming the WIT line.
;;;;
;;;; 1. A plain MVP core module. A string crosses a core boundary as a
;;;;    (pointer, length) pair into the module's exported linear memory, so the
;;;;    host reads it out itself (the async IIFE lets `node -e` use await, which a
;;;;    bare top-level script cannot):
;;;;
;;;;      rontolisp examples/console/mandelbrot-nogc.lisp --no-gc --optimize -o mandelbrot.wasm
;;;;      node -e '(async () => {
;;;;        const ex = (await WebAssembly.instantiate(
;;;;          require("fs").readFileSync("mandelbrot.wasm"), {})).instance.exports;
;;;;        const [ptr, len] = ex.mandelbrot(-2.5, 1.0, -1.2, 1.2, 70, 30, 30);
;;;;        process.stdout.write(
;;;;          Buffer.from(new Uint8Array(ex.memory.buffer, ptr, len)).toString());
;;;;      })()'
;;;;
;;;; 2. A component. The canonical ABI carries the string across and frees it, so
;;;;    the host writes no memory code at all, and no runtime flags:
;;;;
;;;;      rontolisp examples/console/mandelbrot-nogc.lisp --no-gc --component --optimize \
;;;;        --emit-wit -o mandelbrot_component.wasm
;;;;      wasmtime run --invoke 'mandelbrot(-2.5, 1.0, -1.2, 1.2, 70, 30, 30)' \
;;;;        mandelbrot_component.wasm
;;;;
;;;;    (wasmtime prints the RETURNED value, so the art comes back as one escaped
;;;;    string literal rather than rendered; a real host gets the string itself.)
;;;;
;;;; Neither route is this example's discovery. The same relief inside a page,
;;;; through jco-generated bindings, is examples/browser/wit-component/; the
;;;; :string-PARAMETER half of the story -- and what --emit-wit does and does not
;;;; prove -- is examples/count-vowels/. What is worth seeing here is that the two
;;;; builds above come from one unchanged program and one world.

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
  (cond ((>= i max-iter) "#") ((>= i 10) "+") ((>= i 5) ".") (t " ")))

;;; Render the region [x0,x1] x [y0,y1] as a cols x rows grid, accumulating the
;;; characters (and a newline per row) into a single string that is returned.
(defun mandelbrot (x0 x1 y0 y1 cols rows max-iter)
  (let ((dx (/ (- x1 x0) cols)) (dy (/ (- y1 y0) rows)) (out ""))
    (dotimes (r rows)
      (let ((cy (+ y0 (* dy r))))
        (dotimes (c cols)
          (setq out
                (concatenate 'string out
                 (shade (escape-time (+ x0 (* dx c)) cy max-iter) max-iter))))
        (setq out
              (concatenate 'string out
                           "
"))))
    out))

;;; Implement mandelbrot_component.wit, whose world declares
;;;   export mandelbrot: func(x0: f64, ..., max-iter: s32) -> string;
;;; -- seven scalar inputs and a string out, the contract this program is checked
;;; against and the export it gets.
(rontolisp:wit-export "mandelbrot_component.wit")
