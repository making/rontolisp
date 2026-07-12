;; MNIST idx-file loader (the port of the book's dataset/mnist.py).
;;
;; Reads the DECOMPRESSED idx files fetched by ../download-mnist.sh through
;; binary file streams (read-byte over :element-type '(unsigned-byte 8)),
;; which work on every backend -- the WASM targets need the preopen flag
;; --dir . and paths relative to the example root. Reading is byte-at-a-time,
;; so the loaders take a LIMIT (row count) and only consume what a script
;; needs; the full 60000-image train set is never required by the examples.
;;
;; Also hosts the shared big-endian binary helpers and the loader for the
;; book's pretrained ch03 weights (sample-weight.bin, exported from
;; sample_weight.pkl by tools/export-sample-weight.py).

;; --- big-endian binary primitives --------------------------------------------

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
  (dotimes (i n)
    (read-byte s)))

;; --- MNIST loaders ------------------------------------------------------------

(defun mnist-load-images (path &optional (limit 1000) (offset 0))
  ;; LIMIT images from an idx3 image file starting at row OFFSET, as a
  ;; (limit x 784) packed double matrix with pixels normalized to [0, 1]
  ;; (the book loader's normalize=True, flatten=True shape). Doubles keep
  ;; every linalg op in the #d default width, where --simd output is
  ;; bit-identical to the scalar path.
  (with-open-file (s path :element-type '(unsigned-byte 8))
    (let ((magic (%read-be32 s))
          (count (%read-be32 s))
          (rows (%read-be32 s))
          (cols (%read-be32 s)))
      (unless (= magic 2051)
        (error "not an idx3 image file (run ./download-mnist.sh first)"))
      (let* ((pixels (* rows cols))
             (n (min limit (- count offset)))
             (out (make-array (list n pixels) :element-type 'double-float
                              :initial-element 0.0)))
        (%skip-bytes s (* offset pixels))
        (dotimes (i (* n pixels))
          (setf (row-major-aref out i) (/ (read-byte s) 255.0)))
        out))))

(defun mnist-load-labels (path &optional (limit 1000) (offset 0) one-hot)
  ;; LIMIT labels from an idx1 label file starting at row OFFSET: a packed
  ;; double vector of label values 0..9, or with ONE-HOT the (limit x 10)
  ;; one-hot matrix (the book loader's one_hot_label=True).
  (with-open-file (s path :element-type '(unsigned-byte 8))
    (let ((magic (%read-be32 s))
          (count (%read-be32 s)))
      (unless (= magic 2049)
        (error "not an idx1 label file (run ./download-mnist.sh first)"))
      (let* ((n (min limit (- count offset)))
             (lab (make-array n :element-type 'double-float
                              :initial-element 0.0)))
        (%skip-bytes s offset)
        (dotimes (i n)
          (setf (aref lab i) (read-byte s)))
        (if one-hot
            (linalg:one-hot lab 10)
            lab)))))

;; --- pretrained ch03 weights ---------------------------------------------------

(defun load-sample-weight (path)
  ;; The book's pretrained 784-50-100-10 network from sample-weight.bin
  ;; (see tools/export-sample-weight.py for the format), as the plist
  ;; (:w1 W1 :b1 b1 :w2 W2 :b2 b2 :w3 W3 :b3 b3) of packed double arrays.
  (with-open-file (s path :element-type '(unsigned-byte 8))
    (unless (and (= (read-byte s) 82) (= (read-byte s) 76)
                 (= (read-byte s) 87) (= (read-byte s) 49))
      (error "not a sample-weight.bin (RLW1) file"))
    (let ((count (read-byte s))
          (arrays nil))
      (dotimes (k count)
        (let* ((ndim (read-byte s))
               (dims nil))
          (dotimes (d ndim)
            (setq dims (cons (%read-be32 s) dims)))
          (setq dims (reverse dims))
          (let* ((shape (if (cdr dims) dims (car dims)))
                 (a (make-array shape :element-type 'double-float
                                :initial-element 0.0)))
            (dotimes (i (array-total-size a))
              (setf (row-major-aref a i) (%read-f32 s)))
            (setq arrays (cons a arrays)))))
      (let ((v (reverse arrays)))
        (list :w1 (nth 0 v) :b1 (nth 1 v)
              :w2 (nth 2 v) :b2 (nth 3 v)
              :w3 (nth 4 v) :b3 (nth 5 v))))))
