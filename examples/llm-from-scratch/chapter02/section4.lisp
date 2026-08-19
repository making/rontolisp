;; chapter02/section4.lisp -- notebook section 2.4, ported.
;;
;; Cross entropy: what it measures between two distributions, why it is the
;; loss a language model is trained with, and how the general definition
;; collapses onto `torch:cross-entropy-loss' once the target is a one-hot
;; vector -- which is what a next-token target always is.
;;
;; The notebook's bar charts become the discretised distributions themselves.
;;
;;   rontolisp chapter02/section4.lisp

(defun normal-pdf (x mean sigma)
  (let ((z (/ (- x mean) sigma)))
    (/ (exp (* -0.5 z z)) (* sigma (sqrt (* 2.0 pi))))))

(defun discretised-normal (xs mean sigma)
  (let ((out (linalg:zeros (linalg:shape xs))))
    (dotimes (i (linalg:size xs))
      (setf (aref out i) (normal-pdf (aref xs i) mean sigma)))
    out))

(defun cross-entropy (p q)
  ;; The book's numpy definition: -sum(p log q). Note it is NOT symmetric.
  (- (linalg:sum (linalg:mul p (linalg:log q)))))

(defun print-distribution (label p)
  (format t "~a:" label)
  (dotimes (i (linalg:size p)) (format t " ~,3f" (aref p i)))
  (format t "~%"))

;; --- the three discretised distributions ------------------------------------

(defparameter *xs* (linalg:linspace -3.0 3.0 7))

(defparameter *gaussian-1* (discretised-normal *xs* 0.0 1.0))

(defparameter *gaussian-2* (discretised-normal *xs* 0.0 1.5))

(defparameter *uniform* (linalg:full '(7) (/ 1.0 7)))

(print-distribution "N(0, 1.0)  " *gaussian-1*)
(print-distribution "N(0, 1.5)  " *gaussian-2*)
(print-distribution "uniform    " *uniform*)

(format t "H(N(0,1), N(0,1.0)) = ~,4f~%"
        (cross-entropy *gaussian-1* *gaussian-1*))
(format t "H(N(0,1), N(0,1.5)) = ~,4f~%"
        (cross-entropy *gaussian-1* *gaussian-2*))
(format t "H(N(0,1), uniform)  = ~,4f~%" (cross-entropy *gaussian-1* *uniform*))
(format t "closest to itself:    ~a~%"
        (if (and (< (cross-entropy *gaussian-1* *gaussian-1*)
                    (cross-entropy *gaussian-1* *gaussian-2*))
                 (< (cross-entropy *gaussian-1* *gaussian-2*)
                    (cross-entropy *gaussian-1* *uniform*)))
            "yes"
            "no"))

;; --- the one-hot target ------------------------------------------------------
;; A next-token target picks ONE word out of the vocabulary, so p is one-hot and
;; -sum(p log q) is just -log q[k]. That is what torch:cross-entropy-loss
;; computes, straight from the logits, with k as an integer class index.

(defparameter *vocabulary* '("吾輩" "僕" "猫" "犬" "ます" "です" "。"))

(defparameter *one-hot* (linalg:one-hot (linalg:from-list '(2)) 7))

(print-distribution "one-hot    " (linalg:row *one-hot* 0))
(format t "target word:          ~a~%" (nth 2 *vocabulary*))

(defparameter *logits* (torch:tensor '((0.5 1.0 2.5 0.2 0.1 0.4 0.3))))

(defparameter *probabilities* (torch:softmax *logits* :axis -1))

(print-distribution "model q    " (linalg:row (torch:data *probabilities*) 0))
(format t "-log q[2]           = ~,4f~%"
        (- (log (aref (torch:data *probabilities*) 0 2))))
(format t "cross-entropy-loss  = ~,4f~%"
        (torch:item (torch:cross-entropy-loss *logits* '(2))))

;; --- probability targets -----------------------------------------------------
;; nn.CrossEntropyLoss also accepts a full probability vector as the target --
;; the notebook's last cell. torch:cross-entropy-loss takes that spelling too:
;; a target whose SHAPE matches the logits is read as class probabilities, and
;; the loss is -sum(target * log-softmax(logits)) per position.

(defparameter *soft-logits* (torch:tensor '(0.1 0.2 0.7)))

(defparameter *mismatched-target* (torch:tensor '(0.7 0.2 0.1)))

(defparameter *aligned-target* (torch:tensor '(0.1 0.2 0.7)))

(format t "mismatched target   = ~,4f~%"
 (torch:item (torch:cross-entropy-loss *soft-logits* *mismatched-target*)))
(format t "aligned target      = ~,4f~%"
        (torch:item (torch:cross-entropy-loss *soft-logits* *aligned-target*)))
