;; ch07/visualize_filter.py -- the SimpleConvNet's first-layer filters
;; before and after learning (Deep Learning from Scratch).
;;
;; The book imshows the 30 5x5 filters of W1 on a random-initialized net,
;; then again after load_params("params.pkl"). Here each filter renders as
;; ASCII art instead (the ch03 mnist-show ramp), min-max normalized per
;; filter like imshow, 8 filters per row like the book's grid; the trained
;; weights come from ch07/params.bin (params.pkl re-exported through
;; tools/export-sample-weight.py). The random init is seeded, so output is
;; byte-identical on every backend.
;;
;; Data: ch07/params.bin is committed. From examples/deep-learning-from-scratch/:
;;   rontolisp ch07/visualize-filter.lisp
;;   rontolisp ch07/visualize-filter.lisp -o Prog.class && java -cp .:<rontolisp jar> Prog
;;   rontolisp ch07/visualize-filter.lisp -o prog.wasm --optimize && wasmtime run -W gc --dir . prog.wasm
;;   rontolisp ch07/visualize-filter.lisp -o comp.wasm --component && \
;;     wasmtime run -W gc=y --dir . comp.wasm

(load "simple-convnet.lisp")

(defparameter *ramp* " .:-=+*#%@")

(defun %filter-min (w i)
  ;; Smallest weight of filter i's first channel.
  (let* ((d (linalg:shape w))
         (mn (aref w i 0 0 0)))
    (dotimes (y (nth 2 d))
      (dotimes (x (nth 3 d))
        (when (< (aref w i 0 y x) mn)
          (setq mn (aref w i 0 y x)))))
    mn))

(defun %filter-max (w i)
  ;; Largest weight of filter i's first channel.
  (let* ((d (linalg:shape w))
         (mx (aref w i 0 0 0)))
    (dotimes (y (nth 2 d))
      (dotimes (x (nth 3 d))
        (when (> (aref w i 0 y x) mx)
          (setq mx (aref w i 0 y x)))))
    mx))

(defun render-filters (w)
  ;; W (FN C FH FW) as an ASCII grid, 8 filters per row: each filter's
  ;; first-channel plane min-max normalized to the 10-step intensity ramp
  ;; (the book's per-image imshow scaling; denser character = larger
  ;; weight, so the ramp is the book's gray_r inverted).
  (let* ((d (linalg:shape w))
         (fn (car d))
         (fh (nth 2 d))
         (fw (nth 3 d)))
    (do ((base 0 (+ base 8)))
        ((>= base fn))
      (let ((n (min 8 (- fn base))))
        (dotimes (y fh)
          (let ((line ""))
            (dotimes (k n)
              (let* ((i (+ base k))
                     (mn (%filter-min w i))
                     (mx (%filter-max w i))
                     (span (- mx mn)))
                (dotimes (x fw)
                  (let* ((v (aref w i 0 y x))
                         (r (if (= span 0.0) 0 (truncate (* (/ (- v mn) span) 10))))
                         (idx (min 9 r)))
                    (setq line (concatenate 'string line
                                            (subseq *ramp* idx (+ idx 1))))))
                (setq line (concatenate 'string line " "))))
            (write-line line)))
        (terpri)))))

(linalg:seed 42)
(let ((net (make-simple-convnet)))
  (write-line "W1 before learning (seeded random init):")
  (terpri)
  (render-filters (gethash "W1" (scn-params net)))
  (net-load-params net "ch07/params.bin" nil)
  (write-line "W1 after learning (ch07/params.bin):")
  (terpri)
  (render-filters (gethash "W1" (scn-params net))))
