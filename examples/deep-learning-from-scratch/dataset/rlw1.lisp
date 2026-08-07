;; RLW1 pretrained-weight reader + the shared big-endian binary primitives.
;;
;; RLW1 is the simple binary format tools/export-sample-weight.py re-exports
;; the book's pickled numpy params into ("RLW1", u8 count, then per array
;; u8 ndim / u32 dims / big-endian f32 data, row-major); rontolisp cannot
;; read pickle. Parsed byte-at-a-time with read-byte, which works on every
;; backend -- the WASM targets need the preopen flag --dir . and paths
;; relative to the example root.
;;
;; A provide/require module (not a plain load) because it sits on a diamond:
;; dataset/mnist.lisp needs the primitives for the idx headers, and the
;; ch07/ch08 net classes need load-rlw1 for their net-load-params, so a
;; script loading both must splice this file exactly once.

(provide "rlw1")

(defun %read-be32 (s)
  ;; A big-endian unsigned 32-bit integer. Every value read this way in the
  ;; idx/RLW1 headers is far below 2^30, so the arithmetic stays inside the
  ;; WASM i31 integer range.
  (let* ((b0 (read-byte s))
         (b1 (read-byte s))
         (b2 (read-byte s))
         (b3 (read-byte s)))
    (+ (* b0 16777216) (* b1 65536) (* b2 256) b3)))

(defun %read-f32 (s)
  ;; A big-endian IEEE-754 single float, widened exactly to a double. The
  ;; sign/exponent/mantissa are assembled without ever forming the full
  ;; 32-bit word (the 23-bit mantissa is the largest integer built, i31-safe).
  (let* ((b0 (read-byte s))
         (b1 (read-byte s))
         (b2 (read-byte s))
         (b3 (read-byte s))
         (sign (if (>= b0 128) -1.0 1.0))
         (e (+ (* (mod b0 128) 2) (floor (/ b1 128))))
         (m (+ (* (mod b1 128) 65536) (* b2 256) b3)))
    (if (= e 0)
        ;; Zero / subnormal (the book's weights contain none of the latter;
        ;; a subnormal degrades to signed zero here).
        (* sign 0.0)
        (* sign (+ 1.0 (/ m 8388608.0)) (expt 2.0 (- e 127))))))

(defun %skip-bytes (s n)
  ;; Consumes n bytes (there is no seek in the stream API).
  (dotimes (i n) (read-byte s)))

(defun %rlw1-make (shape element-type)
  ;; Both branches take a LITERAL :element-type, so every backend picks the
  ;; packed double[]/float[] representation statically (the linalg
  ;; %la-make pattern); nil / anything else defaults to double.
  (if (eq element-type 'single-float)
      (make-array shape :element-type 'single-float :initial-element 0.0)
      (make-array shape :element-type 'double-float :initial-element 0.0)))

(defun load-rlw1 (path element-type)
  ;; Every array of an RLW1 file, in file order, as a list of packed arrays
  ;; of the requested width (nil = double-float). An f32 value is exact in
  ;; both widths, so 'single-float loses nothing over the file content.
  (with-open-file (s path :element-type '(unsigned-byte 8))
    (unless (and (= (read-byte s) 82) (= (read-byte s) 76) (= (read-byte s) 87)
                 (= (read-byte s) 49))
      (error "not an RLW1 weight file (see tools/export-sample-weight.py)"))
    (let ((count (read-byte s)) (arrays nil))
      (dotimes (k count)
        (let* ((ndim (read-byte s)) (dims nil))
          (dotimes (d ndim) (setq dims (cons (%read-be32 s) dims)))
          (setq dims (reverse dims))
          (let* ((shape (if (cdr dims) dims (car dims)))
                 (a (%rlw1-make shape element-type)))
            (dotimes (i (array-total-size a))
              (setf (row-major-aref a i) (%read-f32 s)))
            (setq arrays (cons a arrays)))))
      (reverse arrays))))
