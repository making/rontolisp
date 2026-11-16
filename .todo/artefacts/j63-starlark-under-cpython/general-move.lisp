;; A radix pass that carries an item beside each key, 200 passes over 50,000: the time
;; without the items and with them (internal time units, ms). As it stands the items' two
;; vectors are declared (simple-array t (*)): 554 / 1219 ms on wasm, 482 / 1663 on the JVM.
;; With that declaration deleted: 620 / 832 and 450 / 1300 -- a move of 21 ns where the
;; declared one is 66 (wasm), 85 where it is 118 (JVM). 2026-11-16.
(defun radix-pass
    (ks ks2 es es2 carried counts size shift digits)
  (declare (type (simple-array (unsigned-byte 32) (*)) ks ks2 counts)
           (type (simple-array t (*)) es es2))
  ;; Each bound and each step is masked: a variable whose range the compiled loop can
  ;; read off its assignments is kept and stepped unboxed on wasm
  ;; (.kb/wasm-unboxed-locals.md, "The exact flavour"). No vector is 2^30 long.
  (let ((n (logand size 1073741823))
        (mask (logand digits 65535))
        (down (logand shift 31))
        (sum 0)
        (i 0))
    (loop while (<= i mask)
          do (setf (aref counts i) 0) (setq i (logand (+ i 1) 1073741823)))
    (setq i 0)
    (loop while (< i n)
          do
            (let ((d (logand (ash (aref ks i) (- down)) mask)))
              (setf (aref counts d) (+ (aref counts d) 1)))
            (setq i (logand (+ i 1) 1073741823)))
    ;; a digit's count becomes the place of its first key
    (setq i 0)
    (loop while (<= i mask)
          do
            (let ((c (aref counts i)))
              (setf (aref counts i) sum)
              (setq sum (logand (+ sum c) 1073741823)))
            (setq i (logand (+ i 1) 1073741823)))
    (setq i 0)
    (loop while (< i n)
          do
            (let* ((k (aref ks i))
                   (d (logand (ash k (- down)) mask))
                   (place (aref counts d)))
              (setf (aref counts d) (+ place 1))
              (setf (aref ks2 place) k)
              (if carried (setf (aref es2 place) (aref es i))))
            (setq i (logand (+ i 1) 1073741823)))))


(defun run (n rounds carried)
  (let ((ks (make-array n :element-type '(unsigned-byte 32)))
        (ks2 (make-array n :element-type '(unsigned-byte 32)))
        (es (make-array n :initial-element 7))
        (es2 (make-array n))
        (counts (make-array 2048 :element-type '(unsigned-byte 32)))
        (x 12345))
    (dotimes (i n)
      (setq x (mod (+ (* x 1103515245) 12345) 2147483648))
      (setf (aref ks i) (mod x 100000)))
    (dotimes (r rounds)
      (radix-pass ks ks2 es es2 carried counts n 0 2047)
      (radix-pass ks2 ks es2 es carried counts n 11 2047))
    (aref ks 100)))
(let ((start (get-internal-real-time)))
  (print (run 50000 100 nil))
  (print (- (get-internal-real-time) start))
  (setq start (get-internal-real-time))
  (print (run 50000 100 t))
  (print (- (get-internal-real-time) start)))
