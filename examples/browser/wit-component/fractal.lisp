;;;; fractal -- a Mandelbrot/Julia explorer that runs in a browser as a
;;;; WebAssembly COMPONENT: the page imports five functions and supplies nothing.
;;;;
;;;; Every other browser demo in this tree loads a raw core module, and the page
;;;; pays for it: rainbow.html hands its text to the module by calling the
;;;; exported bump allocator __ronto_alloc, copying UTF-8 bytes into
;;;; exports.memory.buffer, passing (ptr, len) and decoding the returned
;;;; (ptr, len) back out; the webgl demos hand-write a JavaScript import object
;;;; of dozens of host functions to satisfy their rontolisp:wasm-import
;;;; declarations. None of that is here. This program is compiled to a component:
;;;;
;;;;   rontolisp fractal.lisp -o fractal.wasm --no-gc --component --optimize --emit-wit
;;;;   npx -y @bytecodealliance/jco transpile fractal.wasm -o dist
;;;;
;;;; and the whole of index.html's interface to it is:
;;;;
;;;;   const { mandelbrot, julia, palette, escapeTime, inSet }
;;;;     = await import("./dist/fractal.js");
;;;;
;;;; No WebAssembly.instantiate, no import object, no memory, no allocator, no
;;;; WASI shim. The canonical ABI of the component model moves the strings across
;;;; and frees them, and jco generated those bindings by reading the world out of
;;;; the .wasm. Nothing is downloaded at run time either: jco inlines the core
;;;; module into a single self-contained ES module, so the page is a static file
;;;; any http server can serve.
;;;;
;;;; The world -- wit/fractal.wit -- is the contract, and rontolisp:wit-export at
;;;; the bottom says "this program implements it". The compiler reads the .wit,
;;;; checks the five exports it declares against the defuns below (name, arity,
;;;; parameter and result types) and lowers each into the export directive it
;;;; stands for. A drifted contract is a compile error naming the WIT line, not a
;;;; puzzle in the browser console.
;;;;
;;;; Two shapes here are dictated by the boundary rather than by the mathematics,
;;;; and both are worth knowing before writing a world of your own:
;;;;
;;;;   * The Mandelbrot and Julia renderers are separate exports, although one
;;;;     iteration loop serves both. A wasm-GC callable takes at most seven
;;;;     parameters, so a single render(center-x, center-y, scale, cols, rows,
;;;;     max-iter, julia, cx, cy) would compile under --no-gc and fail on the
;;;;     wasm-GC backend. Six parameters each keeps ONE source compiling on every
;;;;     backend, which is what examples.yaml pins.
;;;;
;;;;   * A frame comes back as a string of palette characters, one per pixel. A
;;;;     component that could return a list<u8> would not need the detour, but a
;;;;     rontolisp component's exports carry scalars and strings only: string is
;;;;     the widest channel available today.
;;;;
;;;; Everything stays inside the --no-gc subset -- floats, integers, string
;;;; literals, (concatenate 'string ...) and (subseq ...); no cons, list, hash or
;;;; I/O -- so the component needs no garbage collector, no WASI and no runtime
;;;; flags.

;;; The characters the renderers may return, darkest first. Index 0 means "inside
;;; the set"; indices 1..63 are the escape-time ramp. The page colors a pixel by
;;; the index of its character here, so this string IS the pixel encoding -- and
;;; it is an export, so the page never hard-codes it.
;;; WIT: palette: func() -> string
(defun palette ()
  "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz+-")

;;; The escape time of the orbit that starts at z = (zx, zy) with the constant
;;; c = (px, py): how many iterations of z <- z^2 + c it survives before |z| > 2
;;; (i.e. |z|^2 > 4), capped at max-iter. Mandelbrot starts z at the origin and
;;; takes c from the pixel; Julia starts z at the pixel and takes c from the
;;; caller. That one swap is the whole difference between the two sets, which is
;;; why both renderers iterate this same function.
(defun iterate (zx zy px py max-iter)
  (let ((i 0))
    (while (and (< i max-iter) (<= (+ (* zx zx) (* zy zy)) 4.0))
      (let ((zt (+ (- (* zx zx) (* zy zy)) px)))
        (setq zy (+ (* 2.0 (* zx zy)) py))
        (setq zx zt))
      (setq i (+ i 1)))
    i))

;;; One escape time as one palette character. The ramp is square-root shaped
;;; because escape times crowd into the low end: sqrt spreads the outer bands
;;; over the whole palette instead of leaving most of the 64 characters unused.
(defun shade (n max-iter)
  (if (>= n max-iter)
      (subseq (palette) 0 1)
      (let ((k (+ 1 (floor (* 63 (sqrt (/ n max-iter)))))))
        (subseq (palette) k (+ k 1)))))

;;; One row of a Mandelbrot / Julia grid, as a string of cols palette characters.
;;;
;;; A row is built separately from the frame on purpose. Growing one string
;;; character by character copies it every time, so accumulating a whole frame
;;; that way costs O((cols*rows)^2) byte copies -- ~600 million for a 240x144
;;; view. Per row it is ~8 million, and the frame renders in milliseconds.
(defun mandelbrot-row (y x0 step cols max-iter)
  (let ((row ""))
    (dotimes (c cols)
      (setq row
            (concatenate 'string row
             (shade (iterate 0.0 0.0 (+ x0 (* step c)) y max-iter) max-iter))))
    row))

(defun julia-row (y x0 step cols max-iter cx cy)
  (let ((row ""))
    (dotimes (c cols)
      (setq row
            (concatenate 'string row
             (shade (iterate (+ x0 (* step c)) y cx cy max-iter) max-iter))))
    row))

;;; Render the Mandelbrot set as a cols x rows grid, row-major, as a single string
;;; of cols * rows palette characters. scale is the width of the view in
;;; complex-plane units; the height follows from the grid's aspect ratio.
;;; WIT: mandelbrot: func(center-x: f64, center-y: f64, scale: f64, cols: s32, rows: s32, max-iter: s32) -> string
(defun mandelbrot (center-x center-y scale cols rows max-iter)
  (let ((step (/ scale cols)) (out ""))
    (let ((x0 (- center-x (/ scale 2.0)))
          (y0 (- center-y (/ (* step rows) 2.0))))
      (dotimes (r rows)
        (setq out
              (concatenate 'string out
               (mandelbrot-row (+ y0 (* step r)) x0 step cols max-iter)))))
    out))

;;; Render the Julia set of the constant cx + cy*i the same way, as a view of
;;; width scale centered on the origin.
;;; WIT: julia: func(cx: f64, cy: f64, scale: f64, cols: s32, rows: s32, max-iter: s32) -> string
(defun julia (cx cy scale cols rows max-iter)
  (let ((step (/ scale cols)) (out ""))
    (let ((x0 (- 0.0 (/ scale 2.0))) (y0 (- 0.0 (/ (* step rows) 2.0))))
      (dotimes (r rows)
        (setq out
              (concatenate 'string out
               (julia-row (+ y0 (* step r)) x0 step cols max-iter cx cy)))))
    out))

;;; The escape time of one point of the Mandelbrot set -- what the page shows for
;;; the point under the cursor.
;;; WIT: escape-time: func(x: f64, y: f64, max-iter: s32) -> s32
(defun escape-time (x y max-iter) (iterate 0.0 0.0 x y max-iter))

;;; Whether the point is in the Mandelbrot set: it survives every iteration.
;;; WIT: in-set: func(x: f64, y: f64, max-iter: s32) -> bool
(defun in-set (x y max-iter) (>= (escape-time x y max-iter) max-iter))

;;; Implement wit/fractal.wit -- the contract this program is checked against,
;;; and the exports it gets. Nothing else in this file is visible to the host.
(rontolisp:wit-export "wit/fractal.wit" :world fractal)
