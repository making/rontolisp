;;;; net.lisp -- the network shared by the trainer and the two inference
;;;; programs (infer.lisp for the CLI, recognize.lisp for the browser).
;;;;
;;;; The demo is a CONVOLUTIONAL net now, and it is not a new one: it is the
;;;; SimpleConvNet of examples/deep-learning-from-scratch/ch07 (the book's
;;;; Conv -> Relu -> Pool -> Affine -> Relu -> Affine over im2col), re-used
;;;; verbatim at this demo's geometry.  Everything it needs -- the CLOS layers,
;;;; Adam, the trainer loop, the RLW1 weight reader -- already lives there, so
;;;; this file only fixes the shape of the problem:
;;;;
;;;;   input   1 x 24 x 24 binary bitmap (the browser's downsampled canvas)
;;;;   conv    16 filters, 5x5, pad 2   -> 16 x 24 x 24
;;;;   pool    2x2 max                  -> 16 x 12 x 12  (= 2304)
;;;;   affine  2304 -> 64, relu
;;;;   affine  64 -> 46                 -> softmax over the 46 gojuon
;;;;
;;;; The weights are NOT baked into the program any more: they are read at
;;;; startup from weights.bin, an RLW1 binary file (the same format the book's
;;;; pretrained params use).  That is what lifts the old 12.5k-parameter ceiling
;;;; -- the JVM backend's class-version-50 verifier caps the number of float
;;;; constants a class may BAKE (.todo/17), not the number it may read.  The
;;;; browser gets the file through the WASI shim's virtual filesystem
;;;; (wasi-shim.js), wasmtime through --dir .

(load "../../deep-learning-from-scratch/ch07/simple-convnet.lisp")

(defparameter *grid* 24)                ; bitmap edge; the input is *grid* x *grid*
(defparameter *pixels* 576)             ; *grid* * *grid*
(defparameter *nclasses* 46)

;; Class index -> romaji label, the output-unit order (must match *romaji* in
;; prototypes.lisp and the K49 remap in tools/k49/prepare-k49.py).  Romaji, not
;; kana, so nothing multibyte crosses the WASM string boundary; the browser maps
;; the label back to a kana for display.
(defparameter *labels*
  (list "a" "i" "u" "e" "o" "ka" "ki" "ku" "ke" "ko" "sa" "shi" "su" "se" "so"
        "ta" "chi" "tsu" "te" "to" "na" "ni" "nu" "ne" "no" "ha" "hi" "fu" "he"
        "ho" "ma" "mi" "mu" "me" "mo" "ya" "yu" "yo" "ra" "ri" "ru" "re" "ro"
        "wa" "wo" "n"))

(defun make-hiragana-net ()
  ;; The ch07 SimpleConvNet at this demo's geometry.  weight-init-std 0.01 is
  ;; the book's; Adam copes with it (see train.lisp).
  (make-simple-convnet :input-dim (list 1 *grid* *grid*)
                       :filter-num 16 :filter-size 5
                       :filter-pad 2 :filter-stride 1
                       :hidden-size 64 :output-size *nclasses*
                       :weight-init-std 0.01))

(defun load-hiragana-net (path)
  ;; A net with the trained weights.bin read back in (RLW1, W1 b1 W2 b2 W3 b3 --
  ;; the ch07 net-load-params contract).
  (net-load-params (make-hiragana-net) path nil))

;; --- RLW1 writer (the trainer's half of the format) ---------------------------
;;
;; dataset/rlw1.lisp READS the format; nothing wrote it in Lisp before (the
;; book's params were exported from Python).  Now the trainer produces one, so
;; the writer lives here: "RLW1", u8 array count, then per array u8 ndim, u32
;; dims (big-endian), and the elements as big-endian IEEE-754 f32, row-major.
;; Only the offline trainer runs this (interpreter / JVM), so it needs no WASM
;; care -- but it is plain arithmetic and would run there too.

(defun %write-be32 (v s)
  (write-byte (mod (floor v 16777216) 256) s)
  (write-byte (mod (floor v 65536) 256) s)
  (write-byte (mod (floor v 256) 256) s)
  (write-byte (mod v 256) s))

(defun %f32-bits (x)
  ;; The IEEE-754 single-precision encoding of X, as the four bytes (b0 b1 b2 b3)
  ;; most-significant first.  Built without bit operations on the raw word: the
  ;; sign, the exponent and the 23-bit mantissa are computed in arithmetic and
  ;; packed, so every intermediate stays small (the i31-safe style of
  ;; dataset/rlw1.lisp's reader, which this inverts).
  (let* ((sign (if (< x 0.0) 128 0))
         (a (abs x)))
    (if (= a 0.0)
        (list sign 0 0 0)
        (let ((e 0))
          ;; Normalize a into [1, 2) tracking the unbiased exponent.
          (while (>= a 2.0) (setq a (/ a 2.0)) (setq e (+ e 1)))
          (while (< a 1.0) (setq a (* a 2.0)) (setq e (- e 1)))
          (let ((m (round (* (- a 1.0) 8388608.0))))
            ;; Rounding the mantissa up to 2^23 carries into the exponent.
            (when (>= m 8388608)
              (setq m 0)
              (setq e (+ e 1)))
            (cond
              ((> e 127)                ; overflow -> +/- infinity
               (list (+ sign 127) 128 0 0))
              ((< e -126)               ; underflow -> signed zero (no subnormals)
               (list sign 0 0 0))
              (t
               (let ((be (+ e 127)))
                 (list (+ sign (floor be 2))
                       (+ (* (mod be 2) 128) (floor m 65536))
                       (mod (floor m 256) 256)
                       (mod m 256))))))))))

(defun %write-f32 (x s)
  (dolist (b (%f32-bits x))
    (write-byte b s)))

(defun save-rlw1 (path arrays)
  ;; Write ARRAYS (a list of packed arrays, any rank) to PATH as one RLW1 file.
  (with-open-file (s path :direction :output :element-type '(unsigned-byte 8))
    (write-byte 82 s) (write-byte 76 s) (write-byte 87 s) (write-byte 49 s)
    (write-byte (length arrays) s)
    (dolist (a arrays)
      (let ((dims (array-dimensions a)))
        (write-byte (length dims) s)
        (dolist (d dims) (%write-be32 d s))
        (dotimes (i (array-total-size a))
          (%write-f32 (row-major-aref a i) s))))))

(defun save-hiragana-net (net path)
  ;; The six parameter arrays in the ch07 key order (*scn-keys*), which is the
  ;; order net-load-params reads them back in.
  (let ((params (scn-params net)))
    (save-rlw1 path
               (list (gethash "W1" params) (gethash "b1" params)
                     (gethash "W2" params) (gethash "b2" params)
                     (gethash "W3" params) (gethash "b3" params)))))

;; --- prediction over ONE bitmap ------------------------------------------------

(defun classify (net flat)
  ;; FLAT is a length-576 packed vector of 0.0/1.0.  Returns the softmax score
  ;; vector over the 46 classes (the net wants a rank-4 (N C H W) batch).
  (linalg:flatten
   (softmax (predict net (linalg:reshape flat (list 1 1 *grid* *grid*))))))

(defun print-prediction (scores)
  ;; The machine-readable lines both front-ends parse: the winner, then every
  ;; class score.
  (let ((pred (linalg:argmax scores)))
    (format t "pred ~a ~a~%" pred (nth pred *labels*))
    (dotimes (i *nclasses*)
      (format t "score ~a ~a~%" (nth i *labels*) (aref scores i)))))
