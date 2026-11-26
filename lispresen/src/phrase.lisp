;;;; phrase.lisp -- Japanese phrase boundaries, a port of BudouX's parser.
;;;;
;;;; A position scores the sum of the weights of the 1-3 character windows around it,
;;;; plus a base of minus half the sum of every weight; it is a boundary when the score is
;;;; positive. Doubling both sides keeps the arithmetic integer.

(in-package :lispresen)

(defvar *budoux-tables* nil "The 13 feature tables, built on first use.")

(defun split-spaces (s)
  (let ((tokens nil) (start 0) (n (length s)))
    (loop
      (let ((sp (position #\Space s :start start)))
        (when (< start (or sp n)) (push (subseq s start (or sp n)) tokens))
        (if sp (setf start (1+ sp)) (return))))
    (nreverse tokens)))

(defun budoux-tables ()
  (or *budoux-tables*
      (setf *budoux-tables*
            (coerce (mapcar (lambda (data)
                              (let ((table (make-hash-table :test #'equal)))
                                (loop for (key weight) on (split-spaces data) by
                                        #'cddr
                                      do
                                        (setf (gethash key table)
                                              (parse-integer weight)))
                                table)) *budoux-ja-groups*) 'simple-vector))))

(defun budoux-breaks (s)
  "A vector whose element I is true when a phrase starts at character I of S."
  (let* ((n (length s))
         (v (make-array n :initial-element nil))
         (tables (budoux-tables)))
    (flet ((w (k start end) (gethash (subseq s start end) (svref tables k) 0)))
      (loop for i from 1 below n
            do
              (let ((score 0))
                (when (> i 2) (incf score (w 0 (- i 3) (- i 2))))
                (when (> i 1) (incf score (w 1 (- i 2) (- i 1))))
                (incf score (w 2 (- i 1) i))
                (incf score (w 3 i (+ i 1)))
                (when (< (+ i 1) n) (incf score (w 4 (+ i 1) (+ i 2))))
                (when (< (+ i 2) n) (incf score (w 5 (+ i 2) (+ i 3))))
                (when (> i 1) (incf score (w 6 (- i 2) i)))
                (incf score (w 7 (- i 1) (+ i 1)))
                (when (< (+ i 1) n) (incf score (w 8 i (+ i 2))))
                (when (> i 2) (incf score (w 9 (- i 3) i)))
                (when (> i 1) (incf score (w 10 (- i 2) (+ i 1))))
                (when (< (+ i 1) n) (incf score (w 11 (- i 1) (+ i 2))))
                (when (< (+ i 2) n) (incf score (w 12 i (+ i 3))))
                (setf (svref v i) (> (- (* 2 score) *budoux-ja-total*) 0)))))
    v))

(defun phrases (s)
  "S split into its phrases."
  (let ((breaks (budoux-breaks s)) (out nil) (start 0))
    (loop for i from 1 below (length s)
          do
            (when (svref breaks i)
              (push (subseq s start i) out)
              (setf start i)))
    (when (< start (length s)) (push (subseq s start) out))
    (nreverse out)))
