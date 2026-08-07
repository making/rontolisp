;; Heat diffusion in a 3-D voxel grid (rank-3 arrays).
;;
;; A 5x5x5 lattice starts with all its heat (1000 units) in the centre voxel
;; and diffuses it step by step to the six axis neighbours, with insulated
;; (no-flux) walls. Because rontolisp arithmetic is exact for integer and
;; rational inputs, every voxel value is an exact ratio and the total heat
;; stays *exactly* 1000 after every step -- identical on every backend.
;; The run stops after 4 steps: with alpha = 1/8 the denominators grow as
;; 8^step, and 4 steps keep every intermediate ratio inside the WASM
;; backend's i31 fixnum range (see doc/en/guides/math-backends.md).
;;
;; Demonstrates: rank-3 make-array / aref / (setf (aref ...)), the #nA
;; printed syntax, array-rank / array-dimensions / array-total-size,
;; row-major-aref for flat scans (and decoding a flat index back into
;; subscripts), and the rank-generic linalg operations (reshape, add, sum,
;; amax, array-equal) over rank-3 tensors.

(defun diffuse (grid alpha)
  ;; One explicit Euler step with insulated boundaries:
  ;; new[c] = old[c] + alpha * sum over the axis neighbours n of (old[n] - old[c]).
  ;; alpha <= 1/6 keeps the step stable (a voxel has at most 6 neighbours).
  (let* ((d (array-dimensions grid))
         (nx (car d))
         (ny (car (cdr d)))
         (nz (car (cdr (cdr d))))
         (out (make-array d :initial-element 0)))
    (dotimes (i nx)
      (dotimes (j ny)
        (dotimes (k nz)
          (let ((c (aref grid i j k)) (acc 0))
            (when (> i 0) (setq acc (+ acc (- (aref grid (- i 1) j k) c))))
            (when (< i (- nx 1))
              (setq acc (+ acc (- (aref grid (+ i 1) j k) c))))
            (when (> j 0) (setq acc (+ acc (- (aref grid i (- j 1) k) c))))
            (when (< j (- ny 1))
              (setq acc (+ acc (- (aref grid i (+ j 1) k) c))))
            (when (> k 0) (setq acc (+ acc (- (aref grid i j (- k 1)) c))))
            (when (< k (- nz 1))
              (setq acc (+ acc (- (aref grid i j (+ k 1)) c))))
            (setf (aref out i j k) (+ c (* alpha acc)))))))
    out))

(defun hottest (grid)
  ;; The (i j k) of the hottest voxel: a flat row-major scan with
  ;; row-major-aref (no nested subscript loops), then the flat index decoded
  ;; back into subscripts. array-row-major-index is the inverse direction.
  (let ((n (array-total-size grid)) (best 0))
    (do ((f 1 (+ f 1)))
        ((>= f n))
      (when (> (row-major-aref grid f) (row-major-aref grid best))
        (setq best f)))
    (let* ((d (array-dimensions grid))
           (ny (car (cdr d)))
           (nz (car (cdr (cdr d))))
           (k (mod best nz))
           (jj (/ (- best k) nz))
           (j (mod jj ny))
           (i (/ (- jj j) ny)))
      (list i j k))))

(defun heat-char (v top)
  ;; A one-character heat map bucket for v relative to the hottest value.
  (cond ((= v 0) " ") ((>= v (/ top 2)) "#") ((>= v (/ top 8)) "+") (t ".")))

(defun print-middle-slice (grid)
  ;; ASCII rendering of the z = middle slice.
  (let* ((d (array-dimensions grid))
         (nx (car d))
         (ny (car (cdr d)))
         (mid (/ (- (car (cdr (cdr d))) 1) 2))
         (top (linalg:amax grid)))
    (dotimes (i nx)
      (dotimes (j ny) (princ (heat-char (aref grid i j mid) top)))
      (terpri))))

(defun main ()
  ;; A small rank-3 tensor tour first: #3A printing, introspection, and the
  ;; rank-generic linalg elementwise operations.
  (let ((tensor (linalg:reshape (linalg:arange 8) '(2 2 2))))
    (format t "tensor:      ~a~%" tensor)
    (format t "rank/dims:   ~a ~a (~a elements)~%" (array-rank tensor)
            (array-dimensions tensor) (array-total-size tensor))
    (format t "add 10:      ~a~%" (linalg:add tensor 10))
    (format t "flat [1 0 1]: index ~a value ~a~%"
            (array-row-major-index tensor 1 0 1)
            (row-major-aref tensor (array-row-major-index tensor 1 0 1)))
    (format t "round trip:  ~a~%~%"
            (linalg:array-equal
             (linalg:reshape (linalg:flatten tensor) '(2 2 2)) tensor)))
  ;; The simulation: all heat starts in the centre voxel.
  (let ((grid (make-array '(5 5 5) :initial-element 0)) (alpha (/ 1 8)))
    (setf (aref grid 2 2 2) 1000)
    (dotimes (step 4)
      (format t "step ~a: total ~a, centre ~a, hottest ~a~%" step
              (linalg:sum grid) (aref grid 2 2 2) (hottest grid))
      (print-middle-slice grid)
      (setq grid (diffuse grid alpha)))
    (format t "step 4: total ~a, centre ~a, hottest ~a~%" (linalg:sum grid)
            (aref grid 2 2 2) (hottest grid))
    (print-middle-slice grid)
    ;; Insulated walls conserve heat exactly: the sum is still the integer
    ;; 1000 after every step, never a float epsilon away.
    (format t "conserved:   ~a~%" (= (linalg:sum grid) 1000))))

(main)
