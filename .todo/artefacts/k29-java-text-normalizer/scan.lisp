(defun scan-a (text limit)
  (let ((n (length text)) (i 0))
    (loop
      (if (or (>= i n) (>= (char-code (char text i)) limit)) (return nil))
      (setq i (+ i 1)))
    (>= i n)))

(defun scan-b (text limit)
  (let ((i 0))
    (loop
      (let ((c (%string-code text i)))
        (if (or (< c 0) (>= c limit)) (return (< c 0))))
      (setq i (+ i 1)))))

(defun scan-c (text limit)
  (let ((i 0) (c 0))
    (loop
      (setq c (%string-code text i))
      (if (or (< c 0) (>= c limit)) (return (< c 0)))
      (setq i (+ i 1)))))

(defun scan-d (text limit)
  (not (find-if (lambda (c) (>= (char-code c) limit)) text)))

(defun timeit (name fn text)
  (let ((start (get-internal-real-time)) (hits 0))
    (dotimes (k 300000) (if (funcall fn text 768) (setq hits (+ hits 1))))
    (format t "~a ~a ~a~%" name (- (get-internal-real-time) start) hits)))

(let ((text (concatenate 'string "The quick brown fox jumps over the lazy dog. 0123456789" (princ-to-string (length "x")))))
  (dotimes (round 3)
    (timeit "a" #'scan-a text)
    (timeit "b" #'scan-b text)
    (timeit "c" #'scan-c text)
    (timeit "d" #'scan-d text))
  (print internal-time-units-per-second))
