;;;; qr.lisp -- QR codes (ISO/IEC 18004) for the deck's URL, drawn as an SVG path.
;;;;
;;;; Byte mode, error correction level M, versions 1 to 10: up to 213 bytes of UTF-8. The
;;;; arithmetic is integer only (GF(256) through log tables), so every backend draws the
;;;; same modules. A matrix is a simple-vector of SIZE x SIZE entries, row by row.

(in-package :lispresen)

(defparameter *qr-blocks*
  #(nil ((16) 10) ((28) 16) ((44) 26) ((32 32) 18) ((43 43) 24)
    ((27 27 27 27) 16) ((31 31 31 31) 18) ((38 38 39 39) 22)
    ((36 36 36 37 37) 22) ((43 43 43 43 44) 26))
  "Per version, the data codewords of each block and the EC codewords per block, level
M.")

(defparameter *qr-alignment*
  #(nil nil (6 18) (6 22) (6 26) (6 30) (6 34) (6 22 38) (6 24 42) (6 26 46)
    (6 28 50))
  "Per version, the rows and columns of the alignment patterns' centers.")

(defparameter *qr-max-bytes* 213)

(defun utf-8-octets (s)
  (let ((out nil))
    (loop for ch across s
          do
            (let ((c (char-code ch)))
              (flet ((cont (shift)
                       (push (logior #x80 (logand (ash c (- shift)) #x3F))
                             out)))
                (cond ((< c #x80) (push c out))
                      ((< c #x800)
                       (push (logior #xC0 (ash c -6)) out)
                       (cont 0))
                      ((< c #x10000)
                       (push (logior #xE0 (ash c -12)) out)
                       (cont 6)
                       (cont 0))
                      (t
                       (push (logior #xF0 (ash c -18)) out)
                       (cont 12)
                       (cont 6)
                       (cont 0))))))
    (nreverse out)))

;;; --- Reed-Solomon over GF(256), polynomial x^8 + x^4 + x^3 + x^2 + 1 -------------

(defun gf-tables ()
  (let ((exp (make-array 510 :initial-element 0))
        (log (make-array 256 :initial-element 0))
        (x 1))
    (dotimes (i 255)
      (setf (svref exp i) x (svref exp (+ i 255)) x (svref log x) i)
      (setf x (ash x 1))
      (when (>= x 256) (setf x (logxor x #x11D))))
    (cons exp log)))

(defparameter *gf* (gf-tables))

(defun gf-mul (a b)
  (if (or (zerop a) (zerop b))
      0
      (svref (car *gf*) (+ (svref (cdr *gf*) a) (svref (cdr *gf*) b)))))

(defun rs-generator (n)
  "The coefficients of (x + a^0) ... (x + a^(n-1)), highest first."
  (let ((g (list 1)))
    (dotimes (i n g)
      (let ((a (svref (car *gf*) i)))
        (setf g
              (mapcar #'logxor (append g (list 0))
                      (cons 0 (mapcar (lambda (c) (gf-mul c a)) g))))))))

(defun rs-ec (data n)
  "The N error correction codewords of the codewords DATA."
  (let ((gen (cdr (rs-generator n))) (rem (make-list n :initial-element 0)))
    (dolist (d data rem)
      (let ((factor (logxor d (car rem))))
        (setf rem
              (mapcar (lambda (r g) (logxor r (gf-mul g factor)))
                      (append (cdr rem) (list 0)) gen))))))

;;; --- codewords --------------------------------------------------------------------

(defun qr-version (octets)
  "The smallest version holding OCTETS, or nil."
  (loop for v from 1 to 10
        when
          (<= (+ 4 (if (< v 10) 8 16) (* 8 (length octets)))
              (* 8 (reduce #'+ (first (svref *qr-blocks* v)))))
        return v))

(defun qr-codewords (octets v)
  "The data of OCTETS in byte mode, padded, split into blocks, error correction added,
and interleaved: the codewords in the order they are placed."
  (destructuring-bind (sizes ec) (svref *qr-blocks* v)
    (let* ((capacity (reduce #'+ sizes)) (bits nil))
      (flet ((put (value width)
               (loop for i from (1- width) downto 0
                     do (push (ldb (byte 1 i) value) bits))))
        (put 4 4)
        (put (length octets) (if (< v 10) 8 16))
        (dolist (o octets) (put o 8))
        (loop repeat (min 4 (- (* 8 capacity) (length bits))) do (push 0 bits))
        (loop until (zerop (mod (length bits) 8)) do (push 0 bits)))
      (let* ((bits (nreverse bits))
             (data
              (loop while bits
                    collect
                      (let ((b 0))
                        (loop repeat 8 do (setf b (+ (* 2 b) (pop bits))))
                        b)))
             (data
              (append data
                      (loop for i from (length data) below capacity
                            collect (if (evenp (- i (length data))) 236 17))))
             (blocks
              (loop for size in sizes
                    collect
                      (prog1 (subseq data 0 size)
                        (setf data (nthcdr size data)))))
             (ecs (mapcar (lambda (b) (rs-ec b ec)) blocks)))
        (append (loop for i from 0 below (reduce #'max sizes)
                      nconc
                        (loop for b in blocks
                              when (< i (length b))
                              collect (nth i b)))
                (loop for i from 0 below ec
                      nconc (mapcar (lambda (e) (nth i e)) ecs)))))))

;;; --- the matrix --------------------------------------------------------------------

(defun qr-format-bits (mask)
  "The 15 format bits of level M and MASK."
  (let* ((data mask) (rem data))
    (dotimes (i 10) (setf rem (logxor (ash rem 1) (* (ash rem -9) #x537))))
    (logxor (logior (ash data 10) rem) #x5412)))

(defun qr-version-bits (v)
  (let ((rem v))
    (dotimes (i 12) (setf rem (logxor (ash rem 1) (* (ash rem -11) #x1F25))))
    (logior (ash v 12) rem)))

(defun qr-mask-p (mask x y)
  (zerop
   (case mask
     (0 (mod (+ x y) 2))
     (1 (mod y 2))
     (2 (mod x 3))
     (3 (mod (+ x y) 3))
     (4 (mod (+ (floor x 3) (floor y 2)) 2))
     (5 (+ (mod (* x y) 2) (mod (* x y) 3)))
     (6 (mod (+ (mod (* x y) 2) (mod (* x y) 3)) 2))
     (t (mod (+ (mod (+ x y) 2) (mod (* x y) 3)) 2)))))

(defun qr-penalty (m n)
  "The mask penalty of the matrix M of side N: runs of five or more, 2 x 2 blocks,
finder-like patterns and the dark balance."
  (let ((score 0) (dark 0))
    (flet ((at (x y) (svref m (+ x (* y n)))))
      (dolist (horizontal '(t nil))
        (dotimes (a n)
          (flet ((cell (b) (if horizontal (at b a) (at a b))))
            (let ((run 1))
              (loop for b from 1 below n
                    do
                      (if (eql (cell b) (cell (1- b)))
                          (incf run)
                          (progn
                            (when (>= run 5) (incf score (- run 2)))
                            (setf run 1))))
              (when (>= run 5) (incf score (- run 2))))
            ;; 1011101 with four light modules on either side.
            (loop for b from 0 to (- n 11)
                  do
                    (dolist (pattern
                             '((1 0 1 1 1 0 1 0 0 0 0) (0 0 0 0 1 0 1 1 1 0 1)))
                      (when (loop for p in pattern
                                  for j from b
                                  always (eql (cell j) p))
                        (incf score 40)))))))
      (dotimes (y (1- n))
        (dotimes (x (1- n))
          (let ((c (at x y)))
            (when (and (eql c (at (1+ x) y)) (eql c (at x (1+ y)))
                       (eql c (at (1+ x) (1+ y))))
              (incf score 3)))))
      (dotimes (i (* n n)) (incf dark (svref m i)))
      (let ((total (* n n)))
        (+ score
           (* 10
              (1-
               (floor (+ (abs (- (* dark 20) (* total 10))) total -1)
                      total))))))))

(defun qr-matrix (octets)
  "The modules (1 dark, 0 light) of the QR code of OCTETS and its side, or nil when it
holds more than version 10 does."
  (let ((v (qr-version octets)))
    (when v
      (let* ((n (+ 17 (* 4 v)))
             (m (make-array (* n n) :initial-element 0))
             (fixed (make-array (* n n) :initial-element nil)))
        (labels ((put (x y dark)
                   (when (and (< -1 x n) (< -1 y n))
                     (setf (svref m (+ x (* y n))) (if dark 1 0)
                           (svref fixed (+ x (* y n))) t)))
                 (format-bits (bits)
                   (flet ((b (i) (logbitp i bits)))
                     (dotimes (i 6) (put 8 i (b i)))
                     (put 8 7 (b 6))
                     (put 8 8 (b 7))
                     (put 7 8 (b 8))
                     (loop for i from 9 below 15 do (put (- 14 i) 8 (b i)))
                     (dotimes (i 8) (put (- n 1 i) 8 (b i)))
                     (loop for i from 8 below 15
                           do (put 8 (+ (- n 15) i) (b i)))
                     (put 8 (- n 8) t))))
          ;; Timing, finders with their separators, alignment patterns.
          (dotimes (i n)
            (put 6 i (evenp i))
            (put i 6 (evenp i)))
          (dolist (c (list (cons 3 3) (cons (- n 4) 3) (cons 3 (- n 4))))
            (loop for dy from -4 to 4
                  do
                    (loop for dx from -4 to 4
                          do
                            (let ((d (max (abs dx) (abs dy))))
                              (put (+ (car c) dx) (+ (cdr c) dy)
                                   (not (member d '(2 4))))))))
          (let* ((pos (svref *qr-alignment* v)) (last (car (last pos))))
            (dolist (cy pos)
              (dolist (cx pos)
                (unless (or (and (= cx 6) (= cy 6)) (and (= cx 6) (= cy last))
                            (and (= cx last) (= cy 6)))
                  (loop for dy from -2 to 2
                        do
                          (loop for dx from -2 to 2
                                do
                                  (put (+ cx dx) (+ cy dy)
                                       (/= (max (abs dx) (abs dy)) 1))))))))
          (format-bits 0)
          (when (>= v 7)
            (let ((bits (qr-version-bits v)))
              (dotimes (i 18)
                (let ((a (+ (- n 11) (mod i 3)))
                      (b (floor i 3))
                      (dark (logbitp i bits)))
                  (put a b dark)
                  (put b a dark)))))
          ;; The codewords, in two-module columns zigzagging up and down from the right.
          (let* ((words (qr-codewords octets v))
                 (total (* 8 (length words)))
                 (i 0)
                 (right (1- n)))
            (loop while (>= right 1)
                  do (when (= right 6) (setf right 5))
                    (dotimes (vert n)
                      (dotimes (j 2)
                        (let* ((x (- right j))
                               (y
                                (if (zerop (logand (1+ right) 2))
                                    (- n 1 vert)
                                    vert)))
                          (when (and (not (svref fixed (+ x (* y n))))
                                     (< i total))
                            (setf (svref m (+ x (* y n)))
                                  (ldb (byte 1 (- 7 (mod i 8)))
                                       (nth (floor i 8) words)))
                            (incf i))))) (decf right 2)))
          ;; The mask with the lowest penalty.
          (flet ((apply-mask (mask)
                   (dotimes (y n)
                     (dotimes (x n)
                       (when (and (not (svref fixed (+ x (* y n))))
                                  (qr-mask-p mask x y))
                         (setf (svref m (+ x (* y n)))
                               (- 1 (svref m (+ x (* y n))))))))))
            (let ((best 0) (best-score nil))
              (dotimes (mask 8)
                (apply-mask mask)
                (format-bits (qr-format-bits mask))
                (let ((score (qr-penalty m n)))
                  (when (or (null best-score) (< score best-score))
                    (setf best mask best-score score)))
                (apply-mask mask))
              (apply-mask best)
              (format-bits (qr-format-bits best))))
          (values m n))))))

(defun write-qr-svg (text size out)
  "The QR code of TEXT as an SVG SIZE px square, dark modules on white with the four
module quiet zone; nil, writing nothing, when TEXT is too long."
  (multiple-value-bind (m n) (qr-matrix (utf-8-octets text))
    (when m
      (let ((side (+ n 8)))
        (format out
                "<svg class=\"qr-code\" viewBox=\"0 0 ~d ~d\" width=\"~d\" height=\"~d\" role=\"img\""
                side side size size)
        (write-attribute "aria-label" (concatenate 'string "QR code: " text)
                         out)
        (format out
                " shape-rendering=\"crispEdges\"><rect width=\"~d\" height=\"~d\" fill=\"#fff\"/><path fill=\"#000\" d=\""
                side side)
        (dotimes (y n)
          (let ((x 0))
            (loop while (< x n)
                  do
                    (if (zerop (svref m (+ x (* y n))))
                        (incf x)
                        (let ((start x))
                          (loop while
                                  (and (< x n) (= 1 (svref m (+ x (* y n)))))
                                do (incf x))
                          (format out "M~d ~dh~dv1h-~dz" (+ start 4) (+ y 4)
                                  (- x start) (- x start)))))))
        (write-string "\"/></svg>" out)
        t))))
